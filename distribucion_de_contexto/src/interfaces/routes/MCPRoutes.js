/**
 * MCP Routes
 * API endpoints for Model Context Protocol operations
 */

import express from 'express';
import { MCPService } from '../../domain/services/MCPService.js';
import { logger } from '../../infrastructure/logging/Logger.js';
import Joi from 'joi';

const router = express.Router();
const mcpService = new MCPService();

// Validation schemas
const querySchema = Joi.object({
  query: Joi.string().required().min(1).max(10000),
  includeRAG: Joi.boolean().optional().default(true),
  includeSchema: Joi.boolean().optional().default(false),
  maxTokens: Joi.number().optional().min(100).max(10000).default(4000)
});

const tableSchema = Joi.object({
  tableName: Joi.string().required(),
  page: Joi.number().optional().min(1).default(1),
  pageSize: Joi.number().optional().min(1).max(100).default(50)
});

/**
 * POST /api/v1/mcp/query
 * Process a query and return enriched context
 */
router.post('/query', async (req, res, next) => {
  try {
    // Validate request
    const { error, value } = querySchema.validate(req.body);
    if (error) {
      return res.status(400).json({
        error: 'Validation Error',
        message: error.details[0].message
      });
    }
    
    const { query, includeRAG, includeSchema, maxTokens } = value;
    
    // Process query
    const result = await mcpService.processQuery(query, {
      includeRAG,
      includeSchema,
      maxTokens
    });
    
    res.json({
      success: true,
      data: result
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/mcp/stats
 * Get database statistics
 */
router.get('/stats', async (req, res, next) => {
  try {
    const stats = await mcpService.getDatabaseStats();
    
    res.json({
      success: true,
      data: stats
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/mcp/table/:tableName
 * Get table data with pagination
 */
router.get('/table/:tableName', async (req, res, next) => {
  try {
    const { tableName } = req.params;
    const page = parseInt(req.query.page) || 1;
    const pageSize = parseInt(req.query.pageSize) || 50;
    
    const result = await mcpService.getTableData(tableName, page, pageSize);
    
    res.json({
      success: true,
      data: result
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/mcp/knowledge
 * Add knowledge to base
 */
router.post('/knowledge', async (req, res, next) => {
  try {
    const { content, metadata } = req.body;
    
    if (!content) {
      return res.status(400).json({
        error: 'Validation Error',
        message: 'content is required'
      });
    }
    
    const result = await mcpService.addKnowledge(content, metadata);
    
    res.json({
      success: true,
      data: result
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
