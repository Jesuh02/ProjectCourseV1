package com.example.tareamov.config

/**
 * Configuration constants for the RAG (Retrieval-Augmented Generation) system
 */
object RAGConfig {
    
    // Context size limits for different LLM services
    const val MAX_LOCAL_LLAMA_CONTEXT = 6 * 1024      // 6KB for local model
    const val MAX_OLLAMA_CONTEXT = 12 * 1024 * 1024   // 12MB for Ollama
    const val MAX_MCP_CONTEXT = 8 * 1024 * 1024       // 8MB for MCP
    
    // Query optimization settings
    const val MAX_QUERY_HISTORY = 10                  // Keep last 10 queries for context
    const val MAX_RETRIEVED_ITEMS = Int.MAX_VALUE     // Sin límite para obtener todos los datos
    const val SEMANTIC_SIMILARITY_THRESHOLD = 0.1     // Reduced threshold for better matching
    
    // Database chunking settings
    const val MAX_CHUNK_SIZE = Int.MAX_VALUE          // Sin límite de tamaño para chunks
    const val CHUNK_OVERLAP = 200                     // Overlap between chunks
    
    // Response formatting
    const val MAX_RESPONSE_LENGTH = Int.MAX_VALUE     // Sin límite para respuestas completas
    const val MAX_LIST_ITEMS = Int.MAX_VALUE          // Sin límite para listas completas
    
    // Cache settings
    const val CONTEXT_CACHE_TTL = 300_000L           // 5 minutes cache TTL
    const val MAX_CACHED_CONTEXTS = 100              // Maximum cached contexts
    
    // Query intent keywords
    val LIST_KEYWORDS = listOf(
        "todos", "listar", "dame", "mostrar", "lista", "enumerar"
    )
    
    val COUNT_KEYWORDS = listOf(
        "cuántos", "cantidad", "número", "contar", "total"
    )
    
    val SEARCH_KEYWORDS = listOf(
        "buscar", "encontrar", "busca", "encuentra", "por", "con", "que contenga"
    )
    
    val RECENT_KEYWORDS = listOf(
        "reciente", "recientes", "último", "últimos", "nuevo", "nuevos"
    )
    
    val ANALYTICS_KEYWORDS = listOf(
        "estadística", "estadísticas", "análisis", "tendencia", "tendencias", "resumen"
    )
    
    // Table semantic tags for better matching - TODAS LAS 14 TABLAS
    val TABLE_SEMANTIC_MAPPING = mapOf(
        "personas" to listOf("persona", "personas", "gente", "individuos", "perfil", "personal"),
        "usuarios" to listOf("usuario", "user", "login", "cuenta", "gente", "persona"),
        "videos" to listOf("video", "contenido", "multimedia", "curso", "leccion", "material"),
        "topics" to listOf("tema", "topico", "categoria", "materia", "asunto", "topic"),
        "content_items" to listOf("contenido", "item", "material", "recurso", "elemento"),
        "tasks" to listOf("tarea", "actividad", "ejercicio", "trabajo", "asignacion", "task"),
        "subscriptions" to listOf("suscripcion", "seguimiento", "seguidor", "subscriptor"),
        "task_submissions" to listOf("entrega", "submission", "respuesta", "envio", "tarea_entregada"),
        "chat_messages" to listOf("mensaje", "chat", "conversacion", "comunicacion"),
        "file_contexts" to listOf("archivo", "file", "contexto", "documento", "subida"),
        "courses" to listOf("curso", "cursos", "formacion", "educacion", "clase"),
        "roles" to listOf("rol", "roles", "permiso", "tipo_usuario", "authority"),
        "recursos" to listOf("recurso", "recursos", "herramienta", "material", "asset"),
        "rol_recursos" to listOf("permisos", "acceso", "autorizacion", "rol_recurso", "privilegios")
    )
    
    // Response templates for different query types
    val RESPONSE_TEMPLATES = mapOf(
        "LIST" to "📋 Aquí tienes la lista solicitada:",
        "COUNT" to "📊 Número total de registros:",
        "SEARCH" to "🔍 Resultados de la búsqueda:",
        "RECENT" to "🕒 Datos más recientes:",
        "ERROR" to "⚠️ Error en la consulta:",
        "EMPTY" to "📭 No se encontraron resultados",
        "ANALYTICS" to "📈 Análisis de datos:"
    )
    
    // System prompts for different contexts
    val SYSTEM_PROMPTS = mapOf(
        "EDUCATIONAL" to """
            Eres un asistente especializado en bases de datos educativas para la plataforma TareaMov.
            Tu objetivo es ayudar a los usuarios a obtener información precisa y útil sobre:
            - Usuarios y sus roles
            - Videos educativos y contenido
            - Temas y organización del contenido
            - Tareas y actividades
            - Suscripciones y seguimientos
            - Cursos estructurados
            - Compras y transacciones
            
            Responde siempre de forma clara, concisa y útil.
        """.trimIndent(),
        
        "ANALYTICAL" to """
            Eres un analista de datos especializado en plataformas educativas.
            Proporciona insights valiosos, estadísticas relevantes y tendencias importantes.
            Enfócate en métricas clave como engagement, crecimiento y uso de la plataforma.
        """.trimIndent(),
        
        "TECHNICAL" to """
            Eres un asistente técnico especializado en consultas de base de datos.
            Ayuda con consultas específicas, filtros complejos y relaciones entre tablas.
            Proporciona información técnica precisa sobre la estructura de datos.
        """.trimIndent()
    )
    
    // Error messages
    val ERROR_MESSAGES = mapOf(
        "NO_CONNECTION" to "❌ No se puede conectar al servicio de base de datos",
        "QUERY_TOO_LARGE" to "⚠️ La consulta es muy compleja, intenta simplificarla",
        "NO_RESULTS" to "📭 No se encontraron resultados para tu consulta",
        "INVALID_QUERY" to "❓ No entiendo tu consulta, puedes reformularla?",
        "SERVICE_ERROR" to "🔧 Error en el servicio, intenta de nuevo en unos momentos"
    )
}
