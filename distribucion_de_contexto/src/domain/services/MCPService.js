/**
 * MCP Service - Model Context Protocol
 * Handles prompt processing and context management
 */

import { RAGService } from './RAGService.js';
import { SupabaseService } from '../../infrastructure/database/SupabaseService.js';
import { logger } from '../../infrastructure/logging/Logger.js';

export class MCPService {
  constructor() {
    this.ragService = new RAGService();
    this.supabase = SupabaseService.getInstance();
  }
  
  /**
   * Process a query and return actual data from Supabase
   */
  async processQuery(query, options = {}) {
    try {
      const {
        includeRAG = true,
        includeSchema = false,
        maxTokens = 4000
      } = options;
      
      logger.info(`Processing query: ${query.substring(0, 100)}...`);
      logger.info(`Full query length: ${query.length} characters`);
      logger.info(`Complete query: "${query}"`);
      
      // Extract SQL query from natural language
      const queryPlan = await this.extractSQLFromQuery(query);
      logger.info(`Query plan generated:`, JSON.stringify(queryPlan));
      
      // Execute the SQL query
      let data = null;
      let sqlScript = null;
      
      if (queryPlan) {
        try {
          const result = await this.executeQuery(queryPlan);
          data = result.data;
          sqlScript = result.sql;
          logger.info(`SQL executed successfully. Data type: ${typeof data}, Array: ${Array.isArray(data)}, Length: ${Array.isArray(data) ? data.length : 'N/A'}`);
        } catch (sqlError) {
          logger.error('SQL execution error:', sqlError);
          logger.error('Error stack:', sqlError.stack);
        }
      } else {
        // If no SQL plan generated, check if the user asked for Business Intelligence / KPIs / architecture
        const lowerQ = (query || '').toLowerCase();
        const biKeywords = ['inteligencia', 'inteligencia de negocio', 'inteligencia de negocios', 'business intelligence', 'kpi', 'kpis', 'indicadores'];
        const askedForBI = biKeywords.some(k => lowerQ.includes(k));

        if (askedForBI) {
          logger.info('Detected BI-style query; returning schema and BI suggestions instead of SQL plan');
          try {
            const schemaObj = await this.getDatabaseSchema();

            // Build simple BI suggestions based on available tables/counts
            let suggestions = [];
            const tables = schemaObj && schemaObj.schema ? schemaObj.schema : schemaObj || {};

            // Prioritize common BI candidates
            if (tables['usuarios'] && tables['usuarios'].exists) {
              suggestions.push('Usuarios: calcular tasa de crecimiento mensual, usuarios activos diarios (DAU), usuarios por rol.');
            }
            if (tables['courses'] && tables['courses'].exists) {
              suggestions.push('Courses: top cursos por suscripciones, tasa de finalización, engagement por curso.');
            }
            if (tables['videos'] && tables['videos'].exists) {
              suggestions.push('Videos: vistas por video, duración promedio, videos que generan más suscripciones.');
            }
            if (tables['subscriptions'] && tables['subscriptions'].exists) {
              suggestions.push('Suscripciones: churn, nuevas suscripciones por periodo, revenue por suscripción (si aplica).');
            }
            if (tables['task_submissions'] && tables['task_submissions'].exists) {
              suggestions.push('Tareas: completitud por estudiante, tiempos promedio de entrega, correlación con engagement.');
            }

            if (suggestions.length === 0) {
              suggestions.push('Recomendar revisar tablas principales y definir KPIs basados en usuarios, contenido y actividad (suscripciones, tareas, interacciones).');
            }

            const biResponse = {
              schema: tables,
              bi_suggestions: suggestions,
              note: 'This response contains the database schema and starter BI suggestions. Use an LLM or analyst to expand into KPIs, SQL examples and an implementation plan.'
            };

            data = biResponse;
            sqlScript = null;
          } catch (schemaErr) {
            logger.error('Error fetching schema for BI response:', schemaErr);
            // fall through to default no-plan behavior
            logger.warn('No query plan generated for query:', query);
          }
        } else {
          logger.warn('No query plan generated for query:', query);
        }
      }
      
      // Prepare response
      const response = {
        data: data,
        sql_script: sqlScript,
        query: query,
        timestamp: new Date().toISOString(),
        metadata: {
          ragEnabled: includeRAG,
          success: data !== null
        }
      };
      
      logger.info(`Query processed successfully. Returned ${data ? (Array.isArray(data) ? data.length : 1) : 0} records`);
      
      return response;
    } catch (error) {
      logger.error('Error processing query:', error);
      throw error;
    }
  }
  
  /**
   * Extract SQL query from natural language
   */
  async extractSQLFromQuery(query) {
    const lowerQuery = query.toLowerCase().trim();
    logger.info(`🔍 Analyzing query: "${lowerQuery}"`);
    
    // FIRST: Check if it's a direct SQL query (SELECT, INSERT, UPDATE, DELETE)
    if (lowerQuery.startsWith('select ') || 
        lowerQuery.startsWith('insert ') || 
        lowerQuery.startsWith('update ') || 
        lowerQuery.startsWith('delete ') ||
        lowerQuery.startsWith('with ')) {
      logger.info('✅ Matched: raw_sql');
      return { operation: 'raw_sql', sql: query };
    }
    
    // Pattern matching for common queries
    
    // "usuarios que no tienen registro en ninguna tabla que no sea usuarios y personas"
    // "usuarios sin registros en otras tablas" or "usuarios aislados"
    // "cuales son los usuarios de los cuales no se tiene registro"
    if ((lowerQuery.includes('usuario')) && 
        (lowerQuery.includes('no tiene') || lowerQuery.includes('sin registro') || 
         lowerQuery.includes('aislado') || lowerQuery.includes('solo en usuarios') || 
         lowerQuery.includes('ninguna tabla') || 
         (lowerQuery.includes('cuales') && lowerQuery.includes('no se tiene')))) {
      // Make sure it's not asking about courses specifically
      if (!lowerQuery.includes('curso') && !lowerQuery.includes('course')) {
        logger.info('✅ Matched: isolated_users');
        return { table: 'usuarios', operation: 'isolated_users' };
      }
    }
    
    // "dame usuarios que no aparecen en courses" or "usuarios sin cursos" or "usuarios que nunca han creado un curso"
    // "lista de usuarios que nunca han creado un curso"
    // "lista de usuario" - broad match for any user list
    if ((lowerQuery.includes('usuarios') || lowerQuery.includes('usuario') || lowerQuery.includes('lista')) && 
        (lowerQuery.includes('no aparecen') || lowerQuery.includes('sin') || lowerQuery.includes('not in') || 
         lowerQuery.includes('nunca') || lowerQuery.includes('never') || lowerQuery.includes('no han') || lowerQuery.includes('no ha')) && 
        (lowerQuery.includes('curso') || lowerQuery.includes('course') || lowerQuery.includes('creado') || lowerQuery.includes('crear'))) {
      logger.info('✅ Matched: not_in_courses');
      return { table: 'usuarios', operation: 'not_in_courses' };
    }
    
    // "lista de usuario" or "todos los usuarios" - generic user list (only if not asking about courses)
    if ((lowerQuery.includes('lista') || lowerQuery.includes('todos') || lowerQuery.includes('dame')) && 
        (lowerQuery.includes('usuario') || lowerQuery.includes('user')) &&
        !lowerQuery.includes('curso') && !lowerQuery.includes('course')) {
      logger.info('✅ Matched: select_all usuarios');
      return { table: 'usuarios', operation: 'select_all' };
    }
    
    // "dame lista única/distinta de creator_username en courses"
    if ((lowerQuery.includes('única') || lowerQuery.includes('unica') || lowerQuery.includes('distinta') || lowerQuery.includes('distinct')) && 
        lowerQuery.includes('creator_username') && 
        lowerQuery.includes('course')) {
      return { table: 'courses', operation: 'distinct_creators' };
    }
    
    // Direct SQL queries - check for SELECT statements
    if (lowerQuery.includes('select') && lowerQuery.includes('from')) {
      // Handle LEFT JOIN queries
      if (lowerQuery.includes('left join') && lowerQuery.includes('where') && lowerQuery.includes('is null')) {
        // Extract table info for LEFT JOIN
        if (lowerQuery.includes('usuarios') && lowerQuery.includes('courses')) {
          return { table: 'usuarios', operation: 'not_in_courses' };
        }
      }
      
      // Handle NOT IN queries
      if (lowerQuery.includes('not in') && lowerQuery.includes('usuarios')) {
        return { table: 'usuarios', operation: 'not_in_courses' };
      }
      
      // Handle DISTINCT queries
      if (lowerQuery.includes('distinct') && lowerQuery.includes('creator_username')) {
        return { table: 'courses', operation: 'distinct_creators' };
      }
    }
    
    // "dame todos los videos" -> SELECT * FROM videos
    if ((lowerQuery.includes('todos') || lowerQuery.includes('all')) && lowerQuery.includes('video')) {
      return { table: 'videos', operation: 'select_all' };
    }
    
    // "dame el video con id=2" -> SELECT * FROM videos WHERE id = 2
    if (lowerQuery.includes('video') && lowerQuery.includes('id') && !lowerQuery.includes('más') && !lowerQuery.includes('mas') && !lowerQuery.includes('antiguo')) {
      const idMatch = lowerQuery.match(/id[=:\s]+(\d+)/);
      if (idMatch) {
        const id = idMatch[1];
        return { table: 'videos', operation: 'select_by_id', id: parseInt(id) };
      }
    }
    
    // "dame el video más antiguo" or "video más antiguo" -> SELECT * FROM videos ORDER BY created_at ASC LIMIT 1
    if (lowerQuery.includes('video') && (lowerQuery.includes('antiguo') || lowerQuery.includes('oldest') || lowerQuery.includes('first'))) {
      return { table: 'videos', operation: 'oldest' };
    }
    
    // "dame el video más reciente" or "video más nuevo" -> SELECT * FROM videos ORDER BY created_at DESC LIMIT 1
    if (lowerQuery.includes('video') && (lowerQuery.includes('reciente') || lowerQuery.includes('nuevo') || lowerQuery.includes('latest') || lowerQuery.includes('newest'))) {
      return { table: 'videos', operation: 'newest' };
    }
    
    // "dame todos los usuarios" -> SELECT * FROM usuarios
    if ((lowerQuery.includes('todos') || lowerQuery.includes('all')) && (lowerQuery.includes('usuarios') || lowerQuery.includes('users'))) {
      return { table: 'usuarios', operation: 'select_all' };
    }
    
    // "dame todos los cursos" or "dame las filas de courses" -> SELECT * FROM courses
    if ((lowerQuery.includes('todos') || lowerQuery.includes('all') || lowerQuery.includes('filas') || lowerQuery.includes('primeras')) && 
        (lowerQuery.includes('curso') || lowerQuery.includes('course'))) {
      // Check if limit is specified
      const limitMatch = lowerQuery.match(/(\d+)\s*(filas|rows)/);
      if (limitMatch) {
        return { table: 'courses', operation: 'select_all', limit: parseInt(limitMatch[1]) };
      }
      return { table: 'courses', operation: 'select_all' };
    }
    
    // "dame el curso con id=X" -> SELECT * FROM courses WHERE id = X
    if (lowerQuery.includes('curso') && lowerQuery.includes('id')) {
      const idMatch = lowerQuery.match(/id[=:\s]+(\d+)/);
      if (idMatch) {
        const id = idMatch[1];
        return { table: 'courses', operation: 'select_by_id', id: parseInt(id) };
      }
    }
    
    // "cuantos videos hay" -> SELECT COUNT(*) FROM videos
    if (lowerQuery.includes('cuantos') || lowerQuery.includes('cuántos') || lowerQuery.includes('how many')) {
      if (lowerQuery.includes('video')) {
        logger.info('✅ Matched: count videos');
        return { table: 'videos', operation: 'count' };
      }
      if (lowerQuery.includes('usuario')) {
        logger.info('✅ Matched: count usuarios');
        return { table: 'usuarios', operation: 'count' };
      }
      if (lowerQuery.includes('curso')) {
        logger.info('✅ Matched: count courses');
        return { table: 'courses', operation: 'count' };
      }
    }
    
    logger.warn(`⚠️ No pattern matched for query: "${lowerQuery}"`);
    return null;
  }
  
  /**
   * Execute query based on the query plan
   */
  async executeQuery(queryPlan) {
    try {
      const { table, operation, id, limit, sql } = queryPlan;
      let query = table ? this.supabase.client.from(table) : null;
      let sqlDescription = '';
      
      switch (operation) {
        case 'raw_sql':
          // Execute raw SQL query directly
          try {
            const result = await this.supabase.executeRawSQL(sql);
            return {
              data: result,
              sql: sql
            };
          } catch (error) {
            logger.error('Raw SQL execution failed:', error);
            throw error;
          }
          
        case 'select_all':
          query = query.select('*');
          if (limit) {
            query = query.limit(limit);
            sqlDescription = `SELECT * FROM ${table} LIMIT ${limit}`;
          } else {
            sqlDescription = `SELECT * FROM ${table}`;
          }
          break;
          
        case 'select_by_id':
          query = query.select('*').eq('id', id).single();
          sqlDescription = `SELECT * FROM ${table} WHERE id = ${id}`;
          break;
          
        case 'oldest':
          query = query.select('*').order('created_at', { ascending: true }).limit(1).single();
          sqlDescription = `SELECT * FROM ${table} ORDER BY created_at ASC LIMIT 1`;
          break;
          
        case 'newest':
          query = query.select('*').order('created_at', { ascending: false }).limit(1).single();
          sqlDescription = `SELECT * FROM ${table} ORDER BY created_at DESC LIMIT 1`;
          break;
          
        case 'count':
          query = query.select('*', { count: 'exact', head: true });
          sqlDescription = `SELECT COUNT(*) FROM ${table}`;
          break;
          
        case 'not_in_courses':
          // Get all usuarios
          const { data: usuarios, error: usuariosError } = await this.supabase.client
            .from('usuarios')
            .select('id, usuario, persona_id, rol_id, created_at');
          
          if (usuariosError) throw usuariosError;
          
          // Get distinct creator_username from courses
          const { data: courses, error: coursesError } = await this.supabase.client
            .from('courses')
            .select('creator_username');
          
          if (coursesError) throw coursesError;
          
          // Extract unique creator usernames
          const creatorUsernames = new Set(courses.map(c => c.creator_username).filter(Boolean));
          
          // Filter usuarios not in courses
          const usuariosNotInCourses = usuarios.filter(u => !creatorUsernames.has(u.usuario));
          
          sqlDescription = `SELECT u.id, u.usuario, u.persona_id, u.rol_id, u.created_at FROM usuarios u LEFT JOIN courses c ON u.usuario = c.creator_username WHERE c.creator_username IS NULL`;
          
          return {
            data: usuariosNotInCourses,
            sql: sqlDescription
          };
          
        case 'isolated_users':
          // Get all usuarios with persona data
          const { data: allUsuarios, error: allUsuariosError } = await this.supabase.client
            .from('usuarios')
            .select(`
              id,
              usuario,
              persona_id,
              personas!usuarios_persona_id_fkey(id, nombres, apellidos, email)
            `);
          
          if (allUsuariosError) throw allUsuariosError;
          
          // Get all tables that might reference usuarios
          const tablesToCheck = [
            { table: 'videos', columns: ['creator_username', 'username', 'uploader', 'creator_usuario_id', 'uploader_id'] },
            { table: 'content_items', columns: ['creator_username', 'creator_usuario_id'] },
            { table: 'courses', columns: ['creator_username'] },
            { table: 'topics', columns: ['creator_username', 'creator_usuario_id'] },
            { table: 'subscriptions', columns: ['creator_username', 'subscriber_username'] },
            { table: 'task_submissions', columns: ['usuario_id', 'creator_username'] },
            { table: 'tasks', columns: ['creator_username', 'usuario_id'] },
            { table: 'chat_messages', columns: ['usuario_id', 'username'] },
            { table: 'file_contexts', columns: ['usuario_id', 'creator_usuario_id', 'username'] }
          ];
          
          // Check each user against all tables
          const isolatedUsers = [];
          
          for (const user of allUsuarios) {
            let hasReferences = false;
            
            // Check each table for references to this user
            for (const { table: tableName, columns } of tablesToCheck) {
              if (hasReferences) break;
              
              try {
                // Build query to check if user exists in this table
                let query = this.supabase.client.from(tableName).select('id', { count: 'exact', head: true });
                
                // Check all possible column references
                const orConditions = [];
                for (const col of columns) {
                  if (col.includes('_id')) {
                    // ID-based reference
                    orConditions.push({ column: col, value: user.id });
                  } else {
                    // Username-based reference
                    orConditions.push({ column: col, value: user.usuario });
                  }
                }
                
                // Execute OR query for each column
                for (const { column, value } of orConditions) {
                  const { count } = await this.supabase.client
                    .from(tableName)
                    .select('id', { count: 'exact', head: true })
                    .eq(column, value);
                  
                  if (count > 0) {
                    hasReferences = true;
                    break;
                  }
                }
              } catch (error) {
                // If table/column doesn't exist, skip it
                logger.error(`Error checking ${tableName}:`, error.message);
              }
            }
            
            // If no references found, user is isolated
            if (!hasReferences) {
              isolatedUsers.push({
                id: user.id,
                usuario: user.usuario,
                nombres: user.personas?.nombres,
                apellidos: user.personas?.apellidos,
                email: user.personas?.email
              });
            }
          }
          
          sqlDescription = `
SELECT u.id, u.usuario, p.nombres, p.apellidos, p.email
FROM usuarios u
LEFT JOIN personas p ON p.id = u.persona_id
WHERE NOT EXISTS (SELECT 1 FROM videos v WHERE v.creator_username = u.usuario OR v.username = u.usuario OR v.uploader = u.usuario OR v.creator_usuario_id = u.id OR v.uploader_id = u.id)
  AND NOT EXISTS (SELECT 1 FROM content_items ci WHERE ci.creator_username = u.usuario OR ci.creator_usuario_id = u.id)
  AND NOT EXISTS (SELECT 1 FROM courses c WHERE c.creator_username = u.usuario)
  AND NOT EXISTS (SELECT 1 FROM topics t WHERE t.creator_username = u.usuario OR t.creator_usuario_id = u.id)
  AND NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.creator_username = u.usuario OR s.subscriber_username = u.usuario)
  AND NOT EXISTS (SELECT 1 FROM task_submissions ts WHERE ts.usuario_id = u.id OR ts.creator_username = u.usuario)
  AND NOT EXISTS (SELECT 1 FROM tasks t2 WHERE t2.creator_username = u.usuario OR t2.usuario_id = u.id)
  AND NOT EXISTS (SELECT 1 FROM chat_messages cm WHERE cm.usuario_id = u.id OR cm.username = u.usuario)
  AND NOT EXISTS (SELECT 1 FROM file_contexts fc WHERE fc.usuario_id = u.id OR fc.creator_usuario_id = u.id OR fc.username = u.usuario)
ORDER BY u.id;
          `.trim();
          
          return {
            data: isolatedUsers,
            sql: sqlDescription
          };

          
        case 'distinct_creators':
          const { data: distinctCreators, error: distinctError } = await this.supabase.client
            .from('courses')
            .select('creator_username');
          
          if (distinctError) throw distinctError;
          
          // Get unique creator usernames
          const uniqueCreators = [...new Set(distinctCreators.map(c => c.creator_username).filter(Boolean))];
          
          sqlDescription = `SELECT DISTINCT creator_username FROM courses`;
          
          return {
            data: uniqueCreators.map(username => ({ creator_username: username })),
            sql: sqlDescription
          };
          
        default:
          throw new Error(`Unknown operation: ${operation}`);
      }
      
      const { data, error, count } = await query;
      
      if (error) {
        logger.error('Query execution error:', error);
        throw error;
      }
      
      // For count operation, return the count
      if (operation === 'count') {
        return {
          data: { count: count || 0 },
          sql: sqlDescription
        };
      }
      
      return {
        data: data,
        sql: sqlDescription
      };
    } catch (error) {
      logger.error('Error executing query:', error);
      throw error;
    }
  }
  
  /**
   * Get database schema
   */
  async getDatabaseSchema() {
    try {
      const schema = await this.supabase.getDatabaseSchema();
      
      return {
        schema,
        timestamp: new Date().toISOString()
      };
    } catch (error) {
      logger.error('Error getting schema:', error);
      throw error;
    }
  }
  
  /**
   * Get database statistics
   */
  async getDatabaseStats() {
    try {
      const schema = await this.ragService.supabase.getDatabaseSchema();
      
      const stats = {
        totalTables: Object.keys(schema).length,
        tables: schema,
        totalRecords: Object.values(schema).reduce((sum, t) => sum + (t.count || 0), 0),
        timestamp: new Date().toISOString()
      };
      
      return stats;
    } catch (error) {
      logger.error('Error getting database stats:', error);
      throw error;
    }
  }
  
  /**
   * Get table data with pagination
   */
  async getTableData(tableName, page = 1, pageSize = 50) {
    try {
      const result = await this.ragService.supabase.getTableData(tableName, page, pageSize);
      
      return result;
    } catch (error) {
      logger.error(`Error getting ${tableName} data:`, error);
      throw error;
    }
  }
  
  /**
   * Search knowledge base
   */
  async searchKnowledge(query, topK = 5) {
    try {
      const results = await this.ragService.search(query, { topK });
      
      return {
        query,
        results,
        count: results.length,
        timestamp: new Date().toISOString()
      };
    } catch (error) {
      logger.error('Error searching knowledge:', error);
      throw error;
    }
  }
  
  /**
   * Add knowledge to base
   */
  async addKnowledge(content, metadata = {}) {
    try {
      const result = await this.ragService.addKnowledge(content, metadata);
      
      return result;
    } catch (error) {
      logger.error('Error adding knowledge:', error);
      throw error;
    }
  }
}

export default MCPService;
