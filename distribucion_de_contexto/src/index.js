/**/**

 * TareaMov MCP Server - Main Entry Point * TareaMov MCP Server - Main Entry Point

 * Official Model Context Protocol Server with Enterprise Features * Official Model Context Protocol Server with Enterprise Features

 */ *

 * Features:

// CRITICAL: Load environment variables FIRST * - RAG (Retrieval-Augmented Generation) with Supabase Vector Store

import './config/env.js'; * - Enterprise-grade security (JWT, API Keys, Rate Limiting)

 * - Clean Architecture (Domain-Driven Design)

import express from 'express'; * - Comprehensive logging and monitoring

import helmet from 'helmet'; * - High availability and performance optimization

import cors from 'cors'; */

import compression from 'compression';

import { createServer } from 'http';// CRITICAL: Load environment variables FIRST, before any other imports

import './config/env.js';

import { logger } from './infrastructure/logging/Logger.js';

import { errorHandler } from './infrastructure/middleware/ErrorHandler.js';import express from 'express';

import { securityMiddleware } from './infrastructure/middleware/SecurityMiddleware.js';import helmet from 'helmet';

import { rateLimiter } from './infrastructure/middleware/RateLimiter.js';import cors from 'cors';

import { requestLogger } from './infrastructure/middleware/RequestLogger.js';import compression from 'compression';

import { createServer } from 'http';

import mcpRouter from './interfaces/routes/MCPRoutes.js';

import healthRouter from './interfaces/routes/HealthRoutes.js';// Import core modules

import ragRouter from './interfaces/routes/RAGRoutes.js';import { logger } from './infrastructure/logging/Logger.js';

import { errorHandler } from './infrastructure/middleware/ErrorHandler.js';

import { SupabaseService } from './infrastructure/database/SupabaseService.js';import { securityMiddleware } from './infrastructure/middleware/SecurityMiddleware.js';

import { CacheService } from './infrastructure/cache/CacheService.js';import { rateLimiter } from './infrastructure/middleware/RateLimiter.js';

import { requestLogger } from './infrastructure/middleware/RequestLogger.js';

// File normalization utilities

const MIME_BY_EXTENSION = {// Import routers

  pdf: 'application/pdf',import mcpRouter from './interfaces/routes/MCPRoutes.js';

  doc: 'application/msword',import healthRouter from './interfaces/routes/HealthRoutes.js';

  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',import ragRouter from './interfaces/routes/RAGRoutes.js';

  txt: 'text/plain',// Import services

  json: 'application/json',import { SupabaseService } from './infrastructure/database/SupabaseService.js';

  ppt: 'application/vnd.ms-powerpoint',    process.exit(1);

  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',function ensureExtension(name, extension) {

  xls: 'application/vnd.ms-excel',  if (!extension) {

  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

};/**

 * TareaMov MCP Server - Main Entry Point

const EXTENSION_BY_MIME = Object.entries(MIME_BY_EXTENSION).reduce((acc, [ext, mime]) => { * Official Model Context Protocol Server with Enterprise Features

  acc[mime] = ext; *

  return acc; * Features:

}, {}); * - RAG (Retrieval-Augmented Generation) with Supabase Vector Store

 * - Enterprise-grade security (JWT, API Keys, Rate Limiting)

const HASH_SUFFIX_PATTERN = /(?:[_-]|%20)?[a-f0-9]{6,}(?=$|\.|_)/gi; * - Clean Architecture (Domain-Driven Design)

 * - Comprehensive logging and monitoring

function parseMetadata(metadata) { * - High availability and performance optimization

  if (!metadata) return {}; */

  if (typeof metadata === 'object') return metadata;

  if (typeof metadata === 'string') {// CRITICAL: Load environment variables FIRST, before any other imports

    const trimmed = metadata.trim();import './config/env.js';

    if (!trimmed) return {};

    try {import express from 'express';

      return JSON.parse(trimmed);import helmet from 'helmet';

    } catch (_) {import cors from 'cors';

      return trimmed.split(';').map(part => part.trim()).filter(Boolean)import compression from 'compression';

        .reduce((acc, segment) => {import { createServer } from 'http';

          const [key, ...rest] = segment.split('=');

          if (key) acc[key.trim()] = rest.join('=').trim();import { logger } from './infrastructure/logging/Logger.js';

          return acc;import { errorHandler } from './infrastructure/middleware/ErrorHandler.js';

        }, {});import { securityMiddleware } from './infrastructure/middleware/SecurityMiddleware.js';

    }import { rateLimiter } from './infrastructure/middleware/RateLimiter.js';

  }import { requestLogger } from './infrastructure/middleware/RequestLogger.js';

  return {};

}import mcpRouter from './interfaces/routes/MCPRoutes.js';

import healthRouter from './interfaces/routes/HealthRoutes.js';

function removeHashSuffix(name) {import ragRouter from './interfaces/routes/RAGRoutes.js';

  return name ? name.replace(HASH_SUFFIX_PATTERN, '') : '';

}import { SupabaseService } from './infrastructure/database/SupabaseService.js';

import { CacheService } from './infrastructure/cache/CacheService.js';

function sanitizeFileNameValue(name) {

  const cleaned = removeHashSuffix(name)const MIME_BY_EXTENSION = {

    .replace(/\s+/g, '_')  pdf: 'application/pdf',

    .replace(/[^A-Za-z0-9._-]/g, '_')  doc: 'application/msword',

    .replace(/_+/g, '_')  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',

    .replace(/^_+|_+$/g, '');  txt: 'text/plain',

  return cleaned || 'archivo';  json: 'application/json',

}  ppt: 'application/vnd.ms-powerpoint',

  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',

function ensureExtension(name, extension) {  xls: 'application/vnd.ms-excel',

  if (!extension) return name;  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

  const lowerName = name.toLowerCase();};

  const lowerExt = extension.toLowerCase();

  if (lowerName.endsWith(`.${lowerExt}`)) return name;const EXTENSION_BY_MIME = Object.entries(MIME_BY_EXTENSION).reduce((acc, [ext, mime]) => {

  const base = name.includes('.') ? name.slice(0, name.lastIndexOf('.')) : name;  acc[mime] = ext;

  const safeBase = base || 'archivo';  return acc;

  return `${safeBase}.${lowerExt}`;}, {});

}

const HASH_SUFFIX_PATTERN = /(?:[_-]|%20)?[a-f0-9]{6,}(?=$|\.|_)/gi;

function deriveNormalizedFileType(fileName = '', explicitType = '', metadataExtension = '') {

  const explicitLower = explicitType.toLowerCase();function parseMetadata(metadata) {

  const metadataLower = metadataExtension.toLowerCase();  if (!metadata) {

    return {};

  if (explicitLower.includes('/')) {  }

    const extensionFromMime = EXTENSION_BY_MIME[explicitLower] || metadataLower;

    return {  if (typeof metadata === 'object') {

      mime: explicitLower,    return metadata;

      extension: extensionFromMime || (fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : '')  }

    };

  }  if (typeof metadata === 'string') {

    const trimmed = metadata.trim();

  const fromName = fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : '';    if (!trimmed) {

  const candidates = [metadataLower, explicitLower, fromName].filter(Boolean);      return {};

  const extension = candidates.find(ext => MIME_BY_EXTENSION[ext]) || candidates[0] || '';    }

  const mime = MIME_BY_EXTENSION[extension] || (extension ? `application/${extension}` : 'application/octet-stream');

  return { mime, extension };    try {

}      return JSON.parse(trimmed);

    } catch (_) {

function normalizeFileDescriptor(descriptor = {}) {      return trimmed

  const metadataMap = parseMetadata(descriptor.metadata);        .split(';')

  const originalName = metadataMap.originalFileName || metadataMap.originalName || descriptor.originalFileName || descriptor.fileName || descriptor.name || 'archivo';        .map(part => part.trim())

  const preferredExtension = metadataMap.extension || metadataMap.ext || descriptor.extension || descriptor.fileExtension || '';        .filter(Boolean)

  const sanitizedBase = sanitizeFileNameValue(originalName);        .reduce((acc, segment) => {

  const { mime, extension: derivedExtension } = deriveNormalizedFileType(          const [key, ...rest] = segment.split('=');

    sanitizedBase,          if (!key) {

    descriptor.fileType || descriptor.mimeType || '',            return acc;

    preferredExtension          }

  );          acc[key.trim()] = rest.join('=').trim();

  const finalExtension = (preferredExtension || derivedExtension || '').toLowerCase();          return acc;

  const normalizedFileName = ensureExtension(sanitizedBase, finalExtension);        }, {});

  const displayName = metadataMap.displayName || metadataMap.originalName || descriptor.displayName || originalName;    }

  }

  return {

    original: descriptor,  return {};

    fileName: normalizedFileName,}

    displayName,

    mimeType: mime,function removeHashSuffix(name) {

    extension: finalExtension || (normalizedFileName.includes('.') ? normalizedFileName.split('.').pop().toLowerCase() : undefined),  if (!name) {

    metadata: metadataMap,    return '';

    contentSummary: descriptor.contentSummary || descriptor.summary || null,  }

    jsonContent: descriptor.jsonContent || null  return name.replace(HASH_SUFFIX_PATTERN, '');

  };}

}

function sanitizeFileNameValue(name) {

function extractDescriptorCandidates(body) {  const cleaned = removeHashSuffix(name)

  if (!body) return [];    .replace(/\s+/g, '_')

  if (Array.isArray(body.fileContexts) && body.fileContexts.length) return body.fileContexts.filter(Boolean);    .replace(/[^A-Za-z0-9._-]/g, '_')

  if (body.fileContext) return [body.fileContext];    .replace(/_+/g, '_')

  if (Array.isArray(body.attachments) && body.attachments.length) return body.attachments.filter(Boolean);    .replace(/^_+|_+$/g, '');

  if (body.attachment) return [body.attachment];

  if (body.file) return [body.file];  return cleaned || 'archivo';

  return [];}

}

function ensureExtension(name, extension) {

function attachFileDescriptor(req, res, next) {  if (!extension) {

  try {    return name;

    const candidates = extractDescriptorCandidates(req.body);  }

    if (!candidates.length) return next();

  const lowerName = name.toLowerCase();

    const normalized = candidates.map(normalizeFileDescriptor);  const lowerExt = extension.toLowerCase();

    req.context = req.context || {};

    req.context.fileDescriptors = normalized;  if (lowerName.endsWith(`.${lowerExt}`)) {

    if (normalized.length === 1) req.context.fileDescriptor = normalized[0];    return name;

    res.locals.fileDescriptors = normalized;  }



    logger.debug(`Attached ${normalized.length} normalized file descriptor(s): ${normalized.map(item => item.fileName).join(', ')}`);  const base = name.includes('.') ? name.slice(0, name.lastIndexOf('.')) : name;

    return next();  const safeBase = base || 'archivo';

  } catch (error) {  return `${safeBase}.${lowerExt}`;

    logger.warn('Failed to normalize file descriptors', error);}

    return next();

  }function deriveNormalizedFileType(fileName = '', explicitType = '', metadataExtension = '') {

}  const explicitLower = explicitType.toLowerCase();

  const metadataLower = metadataExtension.toLowerCase();

// Initialize Express app

const app = express();  if (explicitLower.includes('/')) {

const PORT = process.env.PORT || 3000;    const extensionFromMime = EXTENSION_BY_MIME[explicitLower] || metadataLower;

const HOST = process.env.HOST || '0.0.0.0';    return {

      mime: explicitLower,

// Security middleware      extension: extensionFromMime || (fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : '')

app.use(helmet({    };

  contentSecurityPolicy: {  }

    directives: {

      defaultSrc: ["'self'"],  const fromName = fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : '';

      scriptSrc: ["'self'", "'unsafe-inline'"],  const candidates = [metadataLower, explicitLower, fromName].filter(Boolean);

      styleSrc: ["'self'", "'unsafe-inline'"],  const extension = candidates.find(ext => MIME_BY_EXTENSION[ext]) || candidates[0] || '';

      imgSrc: ["'self'", 'data:', 'https:']  const mime = MIME_BY_EXTENSION[extension] || (extension ? `application/${extension}` : 'application/octet-stream');

    }

  },  return { mime, extension };

  hsts: {}

    maxAge: 31536000,

    includeSubDomains: true,function normalizeFileDescriptor(descriptor = {}) {

    preload: true  const metadataMap = parseMetadata(descriptor.metadata);

  }  const originalName = metadataMap.originalFileName || metadataMap.originalName || descriptor.originalFileName || descriptor.fileName || descriptor.name || 'archivo';

}));  const preferredExtension = metadataMap.extension || metadataMap.ext || descriptor.extension || descriptor.fileExtension || '';

  const sanitizedBase = sanitizeFileNameValue(originalName);

// CORS configuration  const { mime, extension: derivedExtension } = deriveNormalizedFileType(sanitizedBase, descriptor.fileType || descriptor.mimeType || '', preferredExtension);

const corsOptions = {  const finalExtension = (preferredExtension || derivedExtension || '').toLowerCase();

  origin(origin, callback) {  const normalizedFileName = ensureExtension(sanitizedBase, finalExtension);

    const allowedOrigins = process.env.ALLOWED_ORIGINS?.split(',') || ['*'];  const displayName = metadataMap.displayName || metadataMap.originalName || descriptor.displayName || originalName;

    if (!origin) return callback(null, true);

  return {

    const isAllowed = allowedOrigins.some(pattern => {    original: descriptor,

      if (pattern === '*') return true;    fileName: normalizedFileName,

      const regex = new RegExp(pattern.replace(/\*/g, '.*'));    displayName,

      return regex.test(origin);    mimeType: mime,

    });    extension: finalExtension || (normalizedFileName.includes('.') ? normalizedFileName.split('.').pop().toLowerCase() : undefined),

    metadata: metadataMap,

    if (isAllowed) {    contentSummary: descriptor.contentSummary || descriptor.summary || null,

      callback(null, true);    jsonContent: descriptor.jsonContent || null

    } else {  };

      logger.warn(`CORS blocked origin: ${origin}`);}

      callback(new Error('Not allowed by CORS'));

    }function extractDescriptorCandidates(body) {

  },  if (!body) {

  credentials: true,    return [];

  methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'],  }

  allowedHeaders: ['Content-Type', 'Authorization', 'X-API-Key']

};  if (Array.isArray(body.fileContexts) && body.fileContexts.length) {

    return body.fileContexts.filter(Boolean);

app.use(cors(corsOptions));  }

app.use(compression());

app.use(express.json({ limit: '10mb' }));  if (body.fileContext) {

app.use(express.urlencoded({ extended: true, limit: '10mb' }));    return [body.fileContext];

app.use(attachFileDescriptor);  }



// Request processing middleware  if (Array.isArray(body.attachments) && body.attachments.length) {

app.use(requestLogger);    return body.attachments.filter(Boolean);

app.use(rateLimiter);  }

app.use(securityMiddleware);

  if (body.attachment) {

// Routes    return [body.attachment];

app.use('/health', healthRouter);  }

app.use('/api/v1/mcp', mcpRouter);

app.use('/api/v1/rag', ragRouter);  if (body.file) {

    return [body.file];

app.get('/', (req, res) => {  }

  res.json({

    name: 'TareaMov MCP Server',  return [];

    version: '2.0.0',}

    status: 'running',

    timestamp: new Date().toISOString(),function attachFileDescriptor(req, res, next) {

    endpoints: {  try {

      health: '/health',    const candidates = extractDescriptorCandidates(req.body);

      mcp: '/api/v1/mcp',

      rag: '/api/v1/rag'    if (!candidates.length) {

    },      return next();

    documentation: 'https://github.com/Jesuh02/ProjectCourseV1'    }

  });

});    const normalized = candidates.map(normalizeFileDescriptor);



app.use((req, res) => {    req.context = req.context || {};

  res.status(404).json({    req.context.fileDescriptors = normalized;

    error: 'Not Found',    if (normalized.length === 1) {

    message: `Route ${req.method} ${req.path} not found`,      req.context.fileDescriptor = normalized[0];

    timestamp: new Date().toISOString()    }

  });

});    res.locals.fileDescriptors = normalized;



app.use(errorHandler);    logger.debug(

      `Attached ${normalized.length} normalized file descriptor(s): ${normalized

const server = createServer(app);        .map(item => item.fileName)

        .join(', ')}`

async function startServer() {    );

  try {

    logger.info('🚀 Starting TareaMov MCP Server...');    return next();

  } catch (error) {

    logger.info('📊 Connecting to Supabase...');    logger.warn('Failed to normalize file descriptors', error);

    await SupabaseService.getInstance().testConnection();    return next();

    logger.info('✅ Supabase connected successfully');  }

}

    logger.info('💾 Initializing cache...');

    await CacheService.getInstance().connect();const app = express();

    logger.info('✅ Cache initialized');const PORT = process.env.PORT || 3000;

const HOST = process.env.HOST || '0.0.0.0';

    server.listen(PORT, HOST, () => {

      logger.info(`✅ MCP Server running on http://${HOST}:${PORT}`);app.use(helmet({

      logger.info(`📝 Environment: ${process.env.NODE_ENV || 'development'}`);  contentSecurityPolicy: {

      logger.info(`🔒 Security: ${process.env.API_KEY ? 'Enabled' : 'Disabled'}`);    directives: {

      logger.info(`🧠 RAG: ${process.env.RAG_ENABLED === 'true' ? 'Enabled' : 'Disabled'}`);      defaultSrc: ["'self'"],

      logger.info('='.repeat(50));      scriptSrc: ["'self'", "'unsafe-inline'"],

    });      styleSrc: ["'self'", "'unsafe-inline'"],

  } catch (error) {      imgSrc: ["'self'", 'data:', 'https:']

    logger.error('❌ Failed to start server:', error);    }

    process.exit(1);  },

  }  hsts: {

}    maxAge: 31536000,

    includeSubDomains: true,

async function gracefulShutdown(signal) {    preload: true

  logger.info(`\n${signal} received, starting graceful shutdown...`);  }

}));

  server.close(async () => {

    logger.info('HTTP server closed');const corsOptions = {

  origin(origin, callback) {

    try {    const allowedOrigins = process.env.ALLOWED_ORIGINS?.split(',') || ['*'];

      await CacheService.getInstance().disconnect();

      logger.info('Cache disconnected');    if (!origin) {

      logger.info('✅ Graceful shutdown completed');      return callback(null, true);

      process.exit(0);    }

    } catch (error) {

      logger.error('Error during shutdown:', error);    const isAllowed = allowedOrigins.some(pattern => {

      process.exit(1);      if (pattern === '*') {

    }        return true;

  });      }

      const regex = new RegExp(pattern.replace(/\*/g, '.*'));

  setTimeout(() => {      return regex.test(origin);

    logger.error('Forced shutdown due to timeout');    });

    process.exit(1);

  }, 10000);    if (isAllowed) {

}      callback(null, true);

    } else {

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));      logger.warn(`CORS blocked origin: ${origin}`);

process.on('SIGINT', () => gracefulShutdown('SIGINT'));      callback(new Error('Not allowed by CORS'));

    }

process.on('uncaughtException', error => {  },

  logger.error('Uncaught Exception:', error);  credentials: true,

  gracefulShutdown('UNCAUGHT_EXCEPTION');  methods: ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'],

});  allowedHeaders: ['Content-Type', 'Authorization', 'X-API-Key']

};

process.on('unhandledRejection', (reason, promise) => {

  logger.error('Unhandled Rejection at:', promise, 'reason:', reason);app.use(cors(corsOptions));

  gracefulShutdown('UNHANDLED_REJECTION');app.use(compression());

});app.use(express.json({ limit: '10mb' }));

app.use(express.urlencoded({ extended: true, limit: '10mb' }));

startServer();app.use(attachFileDescriptor);



export default app;app.use(requestLogger);

app.use(rateLimiter);
app.use(securityMiddleware);

app.use('/health', healthRouter);
app.use('/api/v1/mcp', mcpRouter);
app.use('/api/v1/rag', ragRouter);

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

app.use((req, res) => {
  res.status(404).json({
    error: 'Not Found',
    message: `Route ${req.method} ${req.path} not found`,
    timestamp: new Date().toISOString()
  });
});

app.use(errorHandler);

const server = createServer(app);

async function startServer() {
  try {
    logger.info('🚀 Starting TareaMov MCP Server...');

    logger.info('📊 Connecting to Supabase...');
    await SupabaseService.getInstance().testConnection();
    logger.info('✅ Supabase connected successfully');

    logger.info('💾 Initializing cache...');
    await CacheService.getInstance().connect();
    logger.info('✅ Cache initialized');

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

async function gracefulShutdown(signal) {
  logger.info(`\n${signal} received, starting graceful shutdown...`);

  server.close(async () => {
    logger.info('HTTP server closed');

    try {
      await CacheService.getInstance().disconnect();
      logger.info('Cache disconnected');

      logger.info('✅ Graceful shutdown completed');
      process.exit(0);
    } catch (error) {
      logger.error('Error during shutdown:', error);
      process.exit(1);
    }
  });

  setTimeout(() => {
    logger.error('Forced shutdown due to timeout');
    process.exit(1);
  }, 10000);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

process.on('uncaughtException', error => {
  logger.error('Uncaught Exception:', error);
  gracefulShutdown('UNCAUGHT_EXCEPTION');
});

process.on('unhandledRejection', (reason, promise) => {
  logger.error('Unhandled Rejection at:', promise, 'reason:', reason);
  gracefulShutdown('UNHANDLED_REJECTION');
});

startServer();

export default app;
