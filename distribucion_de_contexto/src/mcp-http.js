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
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    server: 'tareamov-mcp-server',
    version: '1.0.0',
    timestamp: new Date().toISOString()
  });
});

/**
 * MCP initialize endpoint
 */
app.post('/initialize', async (req, res) => {
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
app.post('/tools/list', async (req, res) => {
  console.log('🔧 Tools list request received');
  
  res.json({
    jsonrpc: '2.0',
    id: req.body.id,
    result: {
      tools: [
        {
          name: 'query_database',
          description: 'Execute natural language queries on the TareaMov database. Returns real-time data from Supabase.',
          inputSchema: {
            type: 'object',
            properties: {
              query: {
                type: 'string',
                description: 'Natural language query in Spanish or English (e.g., "dame todos los usuarios", "show all courses")'
              }
            },
            required: ['query']
          }
        },
        {
          name: 'get_database_schema',
          description: 'Get the complete database schema with all tables and columns',
          inputSchema: {
            type: 'object',
            properties: {}
          }
        }
      ]
    }
  });
});

/**
 * MCP tools/call endpoint
 */
app.post('/tools/call', async (req, res) => {
  const { params } = req.body;
  console.log('🔧 Tool call request:', params?.name);
  
  try {
    const { name, arguments: args } = params;
    
    if (name === 'query_database') {
      const result = await mcpService.processQuery(args.query);
      res.json({
        jsonrpc: '2.0',
        id: req.body.id,
        result: {
          content: [
            {
              type: 'text',
              text: JSON.stringify(result, null, 2)
            }
          ]
        }
      });
    } else if (name === 'get_database_schema') {
      const schema = await mcpService.getDatabaseSchema();
      res.json({
        jsonrpc: '2.0',
        id: req.body.id,
        result: {
          content: [
            {
              type: 'text',
              text: JSON.stringify(schema, null, 2)
            }
          ]
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
