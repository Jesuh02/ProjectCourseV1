/**
 * Supabase Service
 * Handles all database operations with Supabase
 * Implements singleton pattern for connection reuse
 */

import { createClient } from '@supabase/supabase-js';
import { logger } from '../logging/Logger.js';

export class SupabaseService {
    static instance = null;

    constructor() {
        if (SupabaseService.instance) {
            return SupabaseService.instance;
        }

        const supabaseUrl = process.env.SUPABASE_URL;
        const supabaseKey = process.env.SUPABASE_SERVICE_KEY || process.env.SUPABASE_ANON_KEY;

        if (!supabaseUrl || !supabaseKey) {
            throw new Error('Supabase credentials not configured. Set SUPABASE_URL and SUPABASE_SERVICE_KEY in .env');
        }

        this.client = createClient(supabaseUrl, supabaseKey, {
            auth: {
                autoRefreshToken: true,
                persistSession: false
            }
        });

        SupabaseService.instance = this;
    }

    static getInstance() {
        if (!SupabaseService.instance) {
            SupabaseService.instance = new SupabaseService();
        }
        return SupabaseService.instance;
    }

    /**
     * Test database connection
     */
    async testConnection() {
        try {
            const { data, error } = await this.client
                .from('usuarios')
                .select('count')
                .limit(1);

            if (error) throw error;

            logger.info('✅ Supabase connection test successful');
            return true;
        } catch (error) {
            logger.error('❌ Supabase connection test failed:', error);
            throw error;
        }
    }

    /**
     * Get database schema information
     */
    async getDatabaseSchema() {
        try {
            const tables = [
                'personas', 'usuarios', 'videos', 'topics', 'content_items',
                'tasks', 'subscriptions', 'task_submissions', 'chat_messages',
                'file_contexts', 'courses', 'roles', 'recursos', 'rol_recursos'
            ];

            const schema = {};

            for (const table of tables) {
                const { data, error, count } = await this.client
                    .from(table)
                    .select('*', { count: 'exact', head: true });

                if (!error) {
                    schema[table] = {
                        count: count || 0,
                        exists: true
                    };
                }
            }

            return schema;
        } catch (error) {
            logger.error('Error fetching database schema:', error);
            throw error;
        }
    }

    /**
     * Execute RAG query with vector similarity search
     */
    async executeRAGQuery(query, topK = 5) {
        try {
            // Call the vector search RPC function
            const { data, error } = await this.client
                .rpc('search_knowledge_base', {
                    query_text: query,
                    match_threshold: 0.7,
                    match_count: topK
                });

            if (error) throw error;

            return data || [];
        } catch (error) {
            logger.error('RAG query error:', error);
            return [];
        }
    }

    /**
     * Get context for specific tables
     */
    async getTableContext(tableName, filters = {}, limit = 100) {
        try {
            let query = this.client
                .from(tableName)
                .select('*')
                .limit(limit);

            // Apply filters
            for (const [key, value] of Object.entries(filters)) {
                if (value !== undefined && value !== null) {
                    query = query.eq(key, value);
                }
            }

            const { data, error } = await query;

            if (error) throw error;

            return data || [];
        } catch (error) {
            logger.error(`Error fetching ${tableName}:`, error);
            return [];
        }
    }

    /**
     * Get all data from a specific table with pagination
     */
    async getTableData(tableName, page = 1, pageSize = 50) {
        try {
            const from = (page - 1) * pageSize;
            const to = from + pageSize - 1;

            const { data, error, count } = await this.client
                .from(tableName)
                .select('*', { count: 'exact' })
                .range(from, to);

            if (error) throw error;

            return {
                data: data || [],
                total: count || 0,
                page,
                pageSize,
                totalPages: Math.ceil((count || 0) / pageSize)
            };
        } catch (error) {
            logger.error(`Error fetching ${tableName} data:`, error);
            throw error;
        }
    }

    /**
     * Insert data into knowledge base for RAG
     */
    async insertKnowledgeBase(content, metadata = {}) {
        try {
            const { data, error } = await this.client
                .from('knowledge_base')
                .insert([{
                    content,
                    metadata,
                    created_at: new Date().toISOString()
                }])
                .select();

            if (error) throw error;

            return data[0];
        } catch (error) {
            logger.error('Error inserting knowledge base:', error);
            throw error;
        }
    }

    /**
     * Get comprehensive database context for AI
     */
    async getComprehensiveContext(userQuery = '') {
        try {
            const schema = await this.getDatabaseSchema();

            // Build context string
            let context = '# TareaMov Database Context\n\n';
            context += '## Available Tables:\n\n';

            for (const [table, info] of Object.entries(schema)) {
                if (info.exists) {
                    context += `- **${table}**: ${info.count} records\n`;
                }
            }

            context += '\n## Critical Information:\n';
            context += '- **Valid Roles**: usuario, admin (ONLY these two roles exist)\n';
            context += '- **Total Tables**: 14\n';
            context += '- **Primary Features**: Educational platform with courses, videos, tasks\n\n';

            // Add RAG results if query provided
            if (userQuery) {
                const ragResults = await this.executeRAGQuery(userQuery, 3);
                if (ragResults.length > 0) {
                    context += '## Relevant Information (from knowledge base):\n\n';
                    ragResults.forEach((result, index) => {
                        context += `${index + 1}. ${result.content}\n`;
                        context += `   (Similarity: ${(result.similarity * 100).toFixed(1)}%)\n\n`;
                    });
                }
            }

            return context;
        } catch (error) {
            logger.error('Error building comprehensive context:', error);
            throw error;
        }
    }

    /**
     * Health check for Supabase connection
     */
    async healthCheck() {
        try {
            const start = Date.now();
            await this.testConnection();
            const latency = Date.now() - start;

            return {
                status: 'healthy',
                latency: `${latency}ms`,
                timestamp: new Date().toISOString()
            };
        } catch (error) {
            return {
                status: 'unhealthy',
                error: error.message,
                timestamp: new Date().toISOString()
            };
        }
    }

    /**
     * Execute raw SQL query using Supabase RPC
     * This allows executing arbitrary SQL queries
     * Note: Requires a Postgres function 'execute_sql' in your database
     * Or we parse simple SELECT queries and use PostgREST API
     */
    async executeRawSQL(sqlQuery) {
        try {
            logger.info(`Executing raw SQL: ${sqlQuery.substring(0, 100)}...`);

            // 1. Try to execute via RPC 'execute_sql' (Preferred for complex queries)
            try {
                const { data, error } = await this.client.rpc('execute_sql', { query: sqlQuery });
                if (!error) {
                    logger.info('✅ Executed via RPC execute_sql');
                    return data;
                }
                // If error is "function not found", fall back to parser. Otherwise throw.
                if (error.code !== 'PGRST202' && !error.message.includes('function') && !error.message.includes('not found')) {
                    logger.warn(`RPC execute_sql failed with specific error: ${error.message}. Falling back to client-side parsing.`);
                }
            } catch (rpcError) {
                // Fallback to parser
                logger.warn('RPC execute_sql not available or failed, falling back to limited client-side parsing');
            }

            // 2. Fallback: Parse simple SELECT queries client-side
            // First, remove trailing semicolon if present
            let trimmedQuery = sqlQuery.trim();
            if (trimmedQuery.endsWith(';')) {
                trimmedQuery = trimmedQuery.slice(0, -1).trim();
            }

            const lowerTrimmedQuery = trimmedQuery.toLowerCase();

            // Match: SELECT ... FROM table_name ...
            const selectMatch = lowerTrimmedQuery.match(/^select\s+(.+?)\s+from\s+(\w+)(.*)$/i);

            if (selectMatch) {
                const columns = selectMatch[1].trim();
                const tableName = selectMatch[2].trim();
                let rest = selectMatch[3].trim();

                // Remove semicolon from rest as well
                if (rest.endsWith(';')) {
                    rest = rest.slice(0, -1).trim();
                }

                // Check for unsupported complex clauses in fallback mode
                if (rest.includes('join') || rest.includes('group by') || rest.includes('select') || rest.includes('(')) {
                    throw new Error("⛔ STRICT MODE ERROR: Complex queries (JOIN, GROUP BY, SUBQUERIES) are FORBIDDEN. STOP RETRYING THIS QUERY. You MUST use the 'RAW DATA' strategy: 1. Select raw IDs (e.g. 'SELECT student_id FROM task_submissions'). 2. Count/Filter in your head. 3. Select details for specific IDs.");
                }

                // Build query using Supabase client
                let query = this.client.from(tableName);

                // Handle SELECT *
                if (columns === '*') {
                    query = query.select('*');
                } else {
                    // Clean columns (remove aliases like 'as x' because PostgREST doesn't support them in select())
                    // Also remove table prefixes like 'u.username' -> 'username'
                    const cleanColumns = columns.split(',').map(c => {
                        let col = c.trim().split(/\s+as\s+/i)[0]; // Remove alias
                        if (col.includes('.')) {
                            col = col.split('.')[1]; // Remove table prefix
                        }
                        return col;
                    }).join(',');
                    query = query.select(cleanColumns);
                }

                // Parse WHERE clause
                const whereMatch = rest.match(/where\s+(.+?)(?:order\s+by|limit|$)/i);
                if (whereMatch) {
                    let whereClause = whereMatch[1].trim();
                    if (whereClause.endsWith(';')) {
                        whereClause = whereClause.slice(0, -1).trim();
                    }

                    // Handle 'IS NULL'
                    const isNullMatch = whereClause.match(/(\w+)\s+is\s+null/i);
                    if (isNullMatch) {
                        query = query.is(isNullMatch[1], null);
                    }
                    // Handle 'IS NOT NULL'
                    else if (whereClause.match(/(\w+)\s+is\s+not\s+null/i)) {
                        const match = whereClause.match(/(\w+)\s+is\s+not\s+null/i);
                        query = query.not(match[1], 'is', null);
                    }
                    // Handle simple equality 'col = val'
                    else {
                        const eqMatch = whereClause.match(/(\w+)\s*=\s*([^\s;]+)/i);
                        if (eqMatch) {
                            const column = eqMatch[1];
                            let value = eqMatch[2];
                            value = value.replace(/^['"]|['"]$/g, '').replace(/;$/g, '').trim();

                            const lowerVal = String(value).toLowerCase();
                            let finalValue;
                            if (lowerVal === 'true') finalValue = true;
                            else if (lowerVal === 'false') finalValue = false;
                            else if (lowerVal === 'null') finalValue = null;
                            else {
                                const numValue = Number(value);
                                finalValue = isNaN(numValue) ? value : numValue;
                            }

                            query = query.eq(column, finalValue);
                        }
                    }
                }

                // Parse ORDER BY clause
                const orderByMatch = rest.match(/order\s+by\s+(\w+)(?:\s+(asc|desc))?/i);
                if (orderByMatch) {
                    const column = orderByMatch[1];
                    const direction = orderByMatch[2] ? orderByMatch[2].toLowerCase() : 'asc';
                    query = query.order(column, { ascending: direction === 'asc' });
                }

                // Parse LIMIT clause
                const limitMatch = rest.match(/limit\s+(\d+)/i);
                if (limitMatch) {
                    query = query.limit(parseInt(limitMatch[1]));
                }

                // Execute query
                const { data, error } = await query;

                if (error) {
                    logger.error('SQL execution error:', error);
                    throw error;
                }

                return data;
            }

            // If we can't parse it, throw error
            throw new Error('Complex SQL queries (JOIN, GROUP BY, SUBQUERIES) are NOT supported. You MUST retrieve raw data and perform aggregations/joins in your reasoning.');

        } catch (error) {
            logger.error('Error executing raw SQL:', error);
            throw error;
        }
    }
}

export default SupabaseService;