/**
 * MCP Server - HTTP Mode
 * For Android app integration via HTTP
 * Uses JSON-RPC over HTTP (port 3000)
 */

import './config/env.js';
import express from 'express';
import { MCPService } from './domain/services/MCPService.js';
import { SupabaseService } from './infrastructure/database/SupabaseService.js';

const app = express();
const PORT = process.env.MCP_HTTP_PORT || 3000;

// Middleware
app.use(express.json());
app.use((req, res, next) => {
    // Enable CORS for Android emulator
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.header('Access-Control-Allow-Headers', 'Content-Type');
    if (req.method === 'OPTIONS') {
        return res.sendStatus(200);
    }
    next();
});

// Initialize services
const supabase = SupabaseService.getInstance();
const mcpService = new MCPService();

/**
 * Health check endpoint
 */
app.get('/health', async(req, res) => {
    try {
        const dbHealth = await supabase.healthCheck();

        // Test LLM connection
        let llmHealth = { status: 'not_tested' };
        try {
            llmHealth = await mcpService.llmService.testConnection();
        } catch (e) {
            llmHealth = { status: 'error', error: e.message };
        }

        res.json({
            status: 'ok',
            server: 'tareamov-mcp-server',
            version: '1.0.0',
            timestamp: new Date().toISOString(),
            services: {
                database: dbHealth,
                llm: llmHealth
            }
        });
    } catch (error) {
        res.status(500).json({
            status: 'error',
            error: error.message,
            timestamp: new Date().toISOString()
        });
    }
});

/**
 * MCP initialize endpoint
 */
app.post('/initialize', async(req, res) => {
    console.log('🔧 MCP Initialize request received');

    try {
        await supabase.testConnection();
        console.log('✅ Supabase connected');

        res.json({
            jsonrpc: '2.0',
            id: req.body.id,
            result: {
                protocolVersion: '2024-11-05',
                capabilities: {
                    tools: {
                        listChanged: true
                    },
                    resources: {
                        subscribe: true,
                        listChanged: true
                    }
                },
                serverInfo: {
                    name: 'tareamov-mcp-server',
                    version: '1.0.0'
                }
            }
        });
    } catch (error) {
        console.error('❌ Initialize error:', error);
        res.status(500).json({
            jsonrpc: '2.0',
            id: req.body.id,
            error: {
                code: -32603,
                message: 'Internal error during initialization',
                data: error.message
            }
        });
    }
});

/**
 * MCP tools/list endpoint
 */
app.post('/tools/list', async(req, res) => {
    console.log('🔧 Tools list request received');

    res.json({
        jsonrpc: '2.0',
        id: req.body.id,
        result: {
            tools: [{
                    name: 'query_database',
                    description: `Execute queries on the TareaMov Supabase database. Returns real-time data.

**CAPABILITIES:**
- Execute RAW PostgreSQL/SQL queries directly (SELECT, INSERT, UPDATE, DELETE)
- Process natural language queries that are auto-converted to SQL
- Full access to all Supabase tables and relationships
- Supports subqueries and simple JOINs if 'execute_sql' RPC is available.

**HOW TO USE:**
1. **Direct SQL**: Pass valid PostgreSQL syntax directly
   Example: "SELECT id, usuario, email FROM usuarios LIMIT 10"

2. **Natural Language**: Use Spanish or English, system converts to SQL
   Example: "dame todos los usuarios"

**BEST PRACTICES:**
- Always select 'id', 'usuario', 'email' for user queries.
- To check content creation (users who posted content):
  - Courses: match 'courses.creator_user_id' with 'usuarios.id'
  - Tasks: match 'task_submissions.student_username' with 'usuarios.usuario'
- For "users without content" (usuarios sin contenido), you MUST check BOTH tables:
  SELECT id, usuario FROM usuarios 
  WHERE id NOT IN (SELECT creator_user_id FROM courses) 
  AND usuario NOT IN (SELECT student_username FROM task_submissions)
- Use WHERE clauses for filtering
- Use ORDER BY and LIMIT for top/bottom results

**AVAILABLE TABLES:**
usuarios, personas, courses, videos, topics, content_items, tasks, task_submissions, subscriptions, chat_messages, file_contexts, roles, recursos, rol_recursos, progreso_estudiante`,
                    inputSchema: {
                        type: 'object',
                        properties: {
                            query: {
                                type: 'string',
                                description: 'SQL query (preferred) or natural language query. For complex analytics, use raw SQL with JOINs and aggregations.'
                            }
                        },
                        required: ['query']
                    }
                },
                {
                    name: 'get_database_schema',
                    description: `Get the complete Supabase database schema with all tables, columns, types, and relationships.

**RETURNS:**
- Table names and descriptions
- Column names, types, and constraints
- Primary keys and foreign keys
- Relationships between tables

**USE THIS TO:**
- Understand database structure before writing SQL
- Validate column names and types
- Discover relationships for JOINs
- Plan complex queries

**TIP:** Call this ONCE at the start of a conversation, then generate SQL queries based on the schema.`,
                    inputSchema: {
                        type: 'object',
                        properties: {},
                        description: 'No parameters required - returns full schema'
                    }
                }
            ]
        }
    });
});

/**
 * MCP tools/call endpoint
 */
app.post('/tools/call', async(req, res) => {
    const { params } = req.body;
    console.log('🔧 Tool call request:', params ? .name);

    try {
        const { name, arguments: args } = params;

        if (name === 'query_database') {
            let result;
            // Check if it's likely SQL
            const isSQL = /^\s*(select|insert|update|delete|with)\s/i.test(args.query);

            if (isSQL) {
                result = await mcpService.processQuery(args.query, { includeSchema: true });
            } else {
                // Use Agent for Natural Language
                const agentResponse = await mcpService.processQueryWithAgent(args.query);
                result = {
                    data: agentResponse,
                    sqlScript: "-- Executed via Agent"
                };
            }

            res.json({
                jsonrpc: '2.0',
                id: req.body.id,
                result: {
                    content: [{
                        type: 'text',
                        text: JSON.stringify(result, null, 2)
                    }]
                }
            });
        } else if (name === 'get_database_schema') {
            const schema = await mcpService.getDatabaseSchema();
            res.json({
                jsonrpc: '2.0',
                id: req.body.id,
                result: {
                    content: [{
                        type: 'text',
                        text: JSON.stringify(schema, null, 2)
                    }]
                }
            });
        } else {
            res.status(404).json({
                jsonrpc: '2.0',
                id: req.body.id,
                error: {
                    code: -32601,
                    message: `Tool not found: ${name}`
                }
            });
        }
    } catch (error) {
        console.error('❌ Tool call error:', error);
        res.status(500).json({
            jsonrpc: '2.0',
            id: req.body.id,
            error: {
                code: -32603,
                message: 'Tool execution failed',
                data: error.message
            }
        });
    }
});

/**
 * Start server
 */
async function main() {
    console.log('🚀 TareaMov MCP Server starting in HTTP mode...');
    console.log('📊 Connecting to Supabase...');

    try {
        await supabase.testConnection();
        console.log('✅ Supabase connected');

        app.listen(PORT, '0.0.0.0', () => {
            console.log(`✅ MCP HTTP Server listening on http://0.0.0.0:${PORT}`);
            console.log(`📱 Android Emulator URL: http://10.0.2.2:${PORT}`);
            console.log(`💻 Local URL: http://localhost:${PORT}`);
        });
    } catch (error) {
        console.error('❌ Failed to connect to Supabase:', error);
        process.exit(1);
    }
}

main().catch((error) => {
    console.error('❌ Fatal error:', error);
    process.exit(1);
});