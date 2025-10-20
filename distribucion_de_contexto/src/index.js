/**
 * TareaMov MCP Server - Main Entry Point
 * Official Model Context Protocol Server with Enterprise Features
 * 
 * Features:
 * - RAG (Retrieval-Augmented Generation) with Supabase Vector Store
 * - Enterprise-grade security (JWT, API Keys, Rate Limiting)
 * - Clean Architecture (Domain-Driven Design)
 * - Comprehensive logging and monitoring
 * - High availability and performance optimization
 */

// CRITICAL: Load environment variables FIRST, before any other imports
import './config/env.js';

import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import compression from 'compression';
import { createServer } from 'http';

// Import core modules
import { logger } from './infrastructure/logging/Logger.js';
import { errorHandler } from './infrastructure/middleware/ErrorHandler.js';
import { securityMiddleware } from './infrastructure/middleware/SecurityMiddleware.js';
import { rateLimiter } from './infrastructure/middleware/RateLimiter.js';
import { requestLogger } from './infrastructure/middleware/RequestLogger.js';

// Import routers
import mcpRouter from './interfaces/routes/MCPRoutes.js';
import healthRouter from './interfaces/routes/HealthRoutes.js';
import ragRouter from './interfaces/routes/RAGRoutes.js';

// Import services
import { SupabaseService } from './infrastructure/database/SupabaseService.js';
import { CacheService } from './infrastructure/cache/CacheService.js';

const app = express();
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';

// ========================================
// Security Middleware
// ========================================
app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      scriptSrc: ["'self'", "'unsafe-inline'"],
      styleSrc: ["'self'", "'unsafe-inline'"],
      imgSrc: ["'self'", 'data:', 'https:'],
    },
  },
  hsts: {
    maxAge: 31536000,
    includeSubDomains: true,
    preload: true
  }
}));

// CORS Configuration
const corsOptions = {
  origin: function (origin, callback) {
    const allowedOrigins = process.env.ALLOWED_ORIGINS?.split(',') || ['*'];
    
    // Allow requests with no origin (mobile apps, curl, etc.)
    if (!origin) return callback(null, true);
    
    // Check if origin matches any allowed pattern
    const isAllowed = allowedOrigins.some(pattern => {
      if (pattern === '*') return true;
      const regex = new RegExp(pattern.replace(/\*/g, '.*'));
      return regex.test(origin);
    });
    
    if (isAllowed) {
      callback(null, true);
    } else {
      logger.warn(`CORS blocked origin: ${origin}`);
      callback(new Error('Not allowed by CORS'));
    }
  },
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-API-Key']
};

app.use(cors(corsOptions));
app.use(compression());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true, limit: '10mb' }));

// ========================================
// Custom Middleware
// ========================================
app.use(requestLogger);
app.use(rateLimiter);
app.use(securityMiddleware);

// ========================================
// Routes
// ========================================
app.use('/health', healthRouter);
app.use('/api/v1/mcp', mcpRouter);
app.use('/api/v1/rag', ragRouter);

// Root endpoint
app.get('/', (req, res) => {
  res.json({
    name: 'TareaMov MCP Server',
    version: '2.0.0',
    status: 'running',
    timestamp: new Date().toISOString(),
    endpoints: {
      health: '/health',
      mcp: '/api/v1/mcp',
      rag: '/api/v1/rag'
    },
    documentation: 'https://github.com/Jesuh02/ProjectCourseV1'
  });
});

// 404 Handler
app.use((req, res) => {
  res.status(404).json({
    error: 'Not Found',
    message: `Route ${req.method} ${req.path} not found`,
    timestamp: new Date().toISOString()
  });
});

// ========================================
// Error Handler (must be last)
// ========================================
app.use(errorHandler);

// ========================================
// Server Initialization
// ========================================
const server = createServer(app);

async function startServer() {
  try {
    logger.info('🚀 Starting TareaMov MCP Server...');
    
    // Initialize Supabase connection
    logger.info('📊 Connecting to Supabase...');
    await SupabaseService.getInstance().testConnection();
    logger.info('✅ Supabase connected successfully');
    
    // Initialize Cache
    logger.info('💾 Initializing cache...');
    await CacheService.getInstance().connect();
    logger.info('✅ Cache initialized');
    
    // Start HTTP server
    server.listen(PORT, HOST, () => {
      logger.info(`✅ MCP Server running on http://${HOST}:${PORT}`);
      logger.info(`📝 Environment: ${process.env.NODE_ENV || 'development'}`);
      logger.info(`🔒 Security: ${process.env.API_KEY ? 'Enabled' : 'Disabled'}`);
      logger.info(`🧠 RAG: ${process.env.RAG_ENABLED === 'true' ? 'Enabled' : 'Disabled'}`);
      logger.info('='.repeat(50));
    });
    
  } catch (error) {
    logger.error('❌ Failed to start server:', error);
    process.exit(1);
  }
}

// ========================================
// Graceful Shutdown
// ========================================
async function gracefulShutdown(signal) {
  logger.info(`\n${signal} received, starting graceful shutdown...`);
  
  server.close(async () => {
    logger.info('HTTP server closed');
    
    try {
      // Close cache connection
      await CacheService.getInstance().disconnect();
      logger.info('Cache disconnected');
      
      logger.info('✅ Graceful shutdown completed');
      process.exit(0);
    } catch (error) {
      logger.error('Error during shutdown:', error);
      process.exit(1);
    }
  });
  
  // Force shutdown after 10 seconds
  setTimeout(() => {
    logger.error('Forced shutdown due to timeout');
    process.exit(1);
  }, 10000);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

// Handle uncaught exceptions
process.on('uncaughtException', (error) => {
  logger.error('Uncaught Exception:', error);
  gracefulShutdown('UNCAUGHT_EXCEPTION');
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled Rejection at:', promise, 'reason:', reason);
  gracefulShutdown('UNHANDLED_REJECTION');
});

// Start the server
startServer();

export default app;
