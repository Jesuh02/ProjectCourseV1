package com.example.tareamov.service
//
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.FileContext
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Model-Context System Protocol Service
 * Handles advanced database queries and provides context-aware responses
 * with enhanced file conversion capabilities for model context
 */
class MCPService(private val context: Context) {
    private val database: AppDatabase = AppDatabase.getDatabase(context)
    private val databaseQueryService = DatabaseQueryService(context)
    private val mspClient = MSPClient(context)
    private val fileConverterService = FileConverterService(context)
    
    // RAG service for enhanced query processing
    private val ragService by lazy { RAGDatabaseService(context) }
    
    // MCP Tool Service for protocol integration
    private val mcpToolService by lazy { MCPToolService(context) }
    
    // System context to maintain conversation state
    private var conversationContext: String = "general"
    
    // Google Drive API components
    private var driveService: Drive? = null
    private var googleAccountCredential: GoogleAccountCredential? = null

    companion object {
        private const val TAG = "MCPService"
        
        // URL del servidor MCP - Actualiza con la URL real del servidor
        // Usando localhost (127.0.0.1) en lugar de 10.0.2.2 para reducir problemas de conectividad
        private const val MCP_SERVER_URL = "http://127.0.0.1:3000/convert"
        
        // Tiempo máximo de espera para la conversión de archivos grandes y carga de modelos
        // Aumentado para permitir que Ollama cargue el modelo (7+ segundos) + generar respuesta
        private const val TIMEOUT_SECONDS = 300L  // 5 minutos
        
        // URLs alternativos en caso de que el principal falle - EMULADOR PRIMERO
        private val FALLBACK_URLS = listOf(
            "http://10.0.2.2:3000/convert",       // 🎯 EMULADOR -> HOST (MÁXIMA PRIORIDAD)
            "http://192.168.1.16:3000/convert",   // IP Wi-Fi ACTUAL (ipconfig - Oct 10, 2025)
            "http://192.168.1.1:3000/convert",    // Gateway predeterminado (Oct 10, 2025)
            "http://127.0.0.1:3000/convert",      // Localhost
            "http://localhost:3000/convert",      // Localhost alternative
            "http://10.218.57.181:3000/convert",  // IP Wi-Fi anterior
            "http://10.218.57.109:3000/convert",  // Gateway anterior
            "http://172.17.112.1:3000/convert",   // WSL IP
            "http://10.0.2.2:5000/convert",       // Emulator alternate port
            "http://10.0.2.2:8000/convert",       // Emulator alternate port
            "http://127.0.0.1:5000/convert"
        )
    }

    init {
        // Start the Ollama service when MCPService is initialized
        startOllamaService()
        // Initialize Google Drive service
        initializeGoogleDriveService()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS + 60, TimeUnit.SECONDS)  // Extra time for total call
            .build()
    }

    private fun startOllamaService() {
        try {
            val serviceIntent = Intent(context, OllamaService::class.java)
            context.startService(serviceIntent)
            Log.d(TAG, "Started OllamaService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OllamaService", e)
        }
    }

    /**
     * Inicializa el servicio de Google Drive con autenticación
     */
    private fun initializeGoogleDriveService() {
        try {
            // Configurar credenciales de Google Account
            googleAccountCredential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_READONLY)
            )
            
            // Verificar si ya tenemos una cuenta autenticada
            val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(context)
            if (lastSignedInAccount != null) {
                googleAccountCredential?.selectedAccount = lastSignedInAccount.account
                
                // Crear el servicio de Drive
                driveService = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory(),
                    googleAccountCredential
                )
                    .setApplicationName("TareaMov")
                    .build()
                
                Log.d(TAG, "✅ Google Drive service initialized successfully")
            } else {
                Log.d(TAG, "⚠️ No Google account found, user will need to sign in")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing Google Drive service", e)
            driveService = null
        }
    }

    /**
     * Verifica si el usuario está autenticado con Google Drive
     */
    fun isGoogleDriveAuthenticated(): Boolean {
        return driveService != null && googleAccountCredential?.selectedAccount != null
    }

    /**
     * Obtiene las opciones de Google Sign-In para autenticación con Drive
     */
    fun getGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
            .requestEmail()
            .build()
    }

    /**
     * Configura la cuenta de Google después de la autenticación
     */
    fun setGoogleAccount(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        try {
            googleAccountCredential?.selectedAccount = account.account
            
            // Recrear el servicio de Drive con la nueva cuenta
            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory(),
                googleAccountCredential
            )
                .setApplicationName("TareaMov")
                .build()
            
            Log.d(TAG, "✅ Google account configured successfully: ${account.email}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error configuring Google account", e)
        }
    }

    /**
     * Process a query through the MCP system
     */
    /**
     * Process a query through the enhanced RAG system
     */
    suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "=== MCP SERVICE QUERY LOG ===")
            Log.d(TAG, "Input Query: $query")
            Log.d(TAG, "Processing query with RAG enhancement...")
            // Shortcut: detect explicit "field of table by id" requests (e.g. "dame el title del video con id=55")
            try {
                val fid = detectFieldByIdRequest(query)
                if (fid != null) {
                    Log.d(TAG, "Detected field-by-id request: table=${fid.table} id=${fid.id} field=${fid.field}")
                    // Try Supabase first if configured
                    if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        try {
                            // If a specific field was requested, first try a server-side selective query
                            if (fid.field != null) {
                                val normalized = normalizeRequestedField(fid.field)
                                val mappings = mapOf("titulo" to "title", "nombre" to "name", "descripcion" to "description")
                                // candidate column names to try in order
                                val candidates = listOf(normalized, normalized.replace("_", ""), normalized.replace("_", " "), fid.field, mappings[normalized])
                                    .filterNotNull()
                                    .map { it.trim() }
                                    .distinct()

                                for (c in candidates) {
                                    try {
                                        // Build a PostgREST select for a single column. Example: videos?id=eq.38&select=title
                                        val enc = java.net.URLEncoder.encode(c, "UTF-8")
                                        var path = "${fid.table}?id=eq.${fid.id}&select=$enc"
                                        var arr = com.example.tareamov.service.SupabaseClient.fetchTableJson(path)
                                        // If no rows returned, try plural table fallback
                                        if (arr.size() == 0) {
                                            val plural = if (fid.table.endsWith("s")) fid.table else fid.table + "s"
                                            if (plural != fid.table) {
                                                Log.d(TAG, "No rows for ${fid.table} with select=$c, trying plural table: $plural")
                                                path = "${plural}?id=eq.${fid.id}&select=$enc"
                                                arr = com.example.tareamov.service.SupabaseClient.fetchTableJson(path)
                                            }
                                        }
                                        if (arr.size() > 0) {
                                            val first = arr[0].asJsonObject
                                            // try to extract the value for column c (may be returned as the exact name or underscored)
                                            val v = extractFieldFromJson(first, c)
                                            if (v != null) return@withContext v
                                            // also attempt unmapped fallback mapping (e.g., titulo -> title)
                                            mappings[c]?.let { mapped ->
                                                val v2 = extractFieldFromJson(first, mapped)
                                                if (v2 != null) return@withContext v2
                                            }
                                            // if column exists but value is null, try relational fallbacks (e.g. creator_username)
                                            try {
                                                if (fid.field != null) {
                                                    val requested = fid.field.lowercase()
                                                    if (requested.endsWith("username") || requested.contains("creator")) {
                                                        // Look for possible foreign-key fields on the row
                                                        val fkCandidates = listOf("creator_id", "creatorid", "creator", "user_id", "usuario_id", "creatorId")
                                                        var fkVal: Long? = null
                                                        for (fk in fkCandidates) {
                                                            if (first.has(fk) && !first.get(fk).isJsonNull) {
                                                                try {
                                                                    val el = first.get(fk)
                                                                    fkVal = when {
                                                                        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong
                                                                        el.isJsonPrimitive -> el.asString.toLongOrNull()
                                                                        else -> null
                                                                    }
                                                                    if (fkVal != null) break
                                                                } catch (_: Exception) { /* ignore parse issues */ }
                                                            }
                                                        }

                                                        if (fkVal != null) {
                                                            try {
                                                                val usuarios = com.example.tareamov.service.SupabaseClient.fetchUsuarios()
                                                                val user = usuarios.firstOrNull { it.id == fkVal }
                                                                if (user != null) {
                                                                    val userJson = com.google.gson.Gson().toJsonTree(user).asJsonObject
                                                                    val usernameCandidates = listOf("creator_username", "creatorUsername", "username", "user_name", "name")
                                                                    for (pn in usernameCandidates) {
                                                                        val v = extractFieldFromJson(userJson, pn)
                                                                        if (v != null) return@withContext v
                                                                    }
                                                                }
                                                            } catch (e: Exception) {
                                                                Log.w(TAG, "relational lookup for fk=$fkVal failed", e)
                                                            }
                                                        } else {
                                                            // If no numeric FK, maybe the row stores creator username directly in a different key
                                                            val nameKeys = listOf("creator", "creator_username", "creatorname", "author", "author_username")
                                                            for (nk in nameKeys) {
                                                                if (first.has(nk) && !first.get(nk).isJsonNull) {
                                                                    try {
                                                                        val valStr = first.get(nk).asString
                                                                        if (!valStr.isNullOrBlank()) return@withContext valStr
                                                                    } catch (_: Exception) { }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.w(TAG, "relational fallback error", e)
                                            }
                                            // Fallback: return pretty JSON row as final fallback
                                            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                                            return@withContext gson.toJson(first)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "select=$c attempt failed", e)
                                    }
                                }
                            }

                            // If select attempts didn't return a value, fall back to fetching full row by id
                            var tablePath = "${fid.table}?id=eq.${fid.id}"
                            var arr = com.example.tareamov.service.SupabaseClient.fetchTableJson(tablePath)

                            // If no rows were returned, try a simple pluralization fallback (e.g. video -> videos)
                            if (arr.size() == 0) {
                                val plural = if (fid.table.endsWith("s")) fid.table else fid.table + "s"
                                if (plural != fid.table) {
                                    Log.d(TAG, "No rows for ${fid.table}, trying plural table: $plural")
                                    tablePath = "${plural}?id=eq.${fid.id}"
                                    arr = com.example.tareamov.service.SupabaseClient.fetchTableJson(tablePath)
                                }
                            }

                            if (arr.size() > 0) {
                                val first = arr[0].asJsonObject
                                // If a specific field was requested, try to normalize and extract it (different possible key names)
                                if (fid.field != null) {
                                    val normalized = normalizeRequestedField(fid.field)
                                    // Try several candidate names: raw, normalized, and simple translations
                                    val candidates = listOf(normalized, normalized.replace("_", ""), normalized.replace("_", " "), fid.field)
                                    for (c in candidates) {
                                        val v = extractFieldFromJson(first, c)
                                        if (v != null) return@withContext v
                                    }
                                    // Common Spanish->English mappings for field names
                                    val mappings = mapOf("titulo" to "title", "nombre" to "name", "descripcion" to "description")
                                    mappings[normalized]?.let { mapped ->
                                        val v = extractFieldFromJson(first, mapped)
                                        if (v != null) return@withContext v
                                    }
                                }
                                // Before returning the pretty JSON, try relational fallbacks (e.g., resolve creator_username via creator_id)
                                try {
                                    if (fid.field != null) {
                                        val requested = fid.field.lowercase()
                                        if (requested.endsWith("username") || requested.contains("creator")) {
                                            val fkCandidates = listOf("creator_id", "creatorid", "creator", "user_id", "usuario_id", "creatorId")
                                            var fkVal: Long? = null
                                            for (fk in fkCandidates) {
                                                if (first.has(fk) && !first.get(fk).isJsonNull) {
                                                    try {
                                                        val el = first.get(fk)
                                                        fkVal = when {
                                                            el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong
                                                            el.isJsonPrimitive -> el.asString.toLongOrNull()
                                                            else -> null
                                                        }
                                                        if (fkVal != null) break
                                                    } catch (_: Exception) { }
                                                }
                                            }

                                            if (fkVal != null) {
                                                try {
                                                    val usuarios = com.example.tareamov.service.SupabaseClient.fetchUsuarios()
                                                    val user = usuarios.firstOrNull { it.id == fkVal }
                                                    if (user != null) {
                                                        val userJson = com.google.gson.Gson().toJsonTree(user).asJsonObject
                                                        val usernameCandidates = listOf("creator_username", "creatorUsername", "username", "user_name", "name")
                                                        for (pn in usernameCandidates) {
                                                            val v = extractFieldFromJson(userJson, pn)
                                                            if (v != null) return@withContext v
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "relational lookup for fk=$fkVal failed", e)
                                                }
                                            } else {
                                                val nameKeys = listOf("creator", "creator_username", "creatorname", "author", "author_username")
                                                for (nk in nameKeys) {
                                                    if (first.has(nk) && !first.get(nk).isJsonNull) {
                                                        try {
                                                            val valStr = first.get(nk).asString
                                                            if (!valStr.isNullOrBlank()) return@withContext valStr
                                                        } catch (_: Exception) { }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "relational fallback error", e)
                                }
                                // Fallback: return pretty JSON of the object
                                val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                                return@withContext gson.toJson(first)
                            } else {
                                Log.d(TAG, "Supabase returned no rows for ${fid.table} id=${fid.id}")
                                // Try typed Supabase fetcher fallbacks (e.g., fetchVideos()) as a last-resort client-side match
                                try {
                                    val plural = if (fid.table.endsWith("s")) fid.table else fid.table + "s"
                                    when (plural) {
                                        "videos" -> {
                                            val list = com.example.tareamov.service.SupabaseClient.fetchVideos()
                                            val found = list.firstOrNull { it.id == fid.id }
                                            if (found != null) {
                                                if (fid.field != null) {
                                                    val json = com.google.gson.Gson().toJsonTree(found).asJsonObject
                                                    val norm = normalizeRequestedField(fid.field)
                                                    val candidates = listOf(norm, norm.replace("_", ""), norm.replace("_", " "), fid.field)
                                                    for (c in candidates) {
                                                        val v = extractFieldFromJson(json, c)
                                                        if (v != null) return@withContext v
                                                    }
                                                }
                                                return@withContext com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(found)
                                            }
                                        }
                                        "personas" -> {
                                            val list = com.example.tareamov.service.SupabaseClient.fetchPersonas()
                                            val found = list.firstOrNull { it.id == fid.id }
                                            if (found != null) return@withContext com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(found)
                                        }
                                        "usuarios" -> {
                                            val list = com.example.tareamov.service.SupabaseClient.fetchUsuarios()
                                            val found = list.firstOrNull { it.id == fid.id }
                                            if (found != null) return@withContext com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(found)
                                        }
                                        // add more typed fallbacks as needed
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Typed Supabase fallback failed", e)
                                }
                                // fallthrough to local DB fallback
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Supabase fetch error for field-by-id", e)
                        }
                    }

                    // Local DB fallback: try to find the row in Room and return field or JSON
                    try {
                        when (fid.table) {
                            "videos" -> {
                                val list = database.videoDao().getAllVideos()
                                val found = list.firstOrNull { it.id == fid.id }
                                if (found != null) {
                                    if (fid.field != null) {
                                        // Try common field names
                                        val possible = listOf(fid.field, "title", "titulo", "name", "nombre")
                                        for (p in possible) {
                                            val json = com.google.gson.Gson().toJsonTree(found).asJsonObject
                                            val v = extractFieldFromJson(json, p)
                                            if (v != null) return@withContext v
                                        }
                                    }
                                    val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                                    return@withContext gson.toJson(found)
                                }
                            }
                            else -> {
                                // Generic fallback: try Supabase again without select (if not configured earlier)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Local DB fallback failed for field-by-id", e)
                    }

                    return@withContext "No hay información disponible sobre ${fid.field ?: "la fila"} con id=${fid.id} en la tabla ${fid.table}."
                }
            } catch (t: Throwable) {
                Log.w(TAG, "field-by-id detection failed", t)
            }

            // Shortcut: detect explicit table fetch requests and answer directly from Supabase
            try {
                val requestedTable = detectTableFetchRequest(query)
                if (requestedTable != null) {
                    Log.d(TAG, "Detected direct table request for: $requestedTable")
                    // Ensure SupabaseClient is configured
                    if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        Log.d(TAG, "SupabaseClient not configured - cannot fetch table")
                        return@withContext "Supabase no está configurado. Configure SUPABASE_URL/SUPABASE_KEY o inyecte la API key para permitir consultas directas."
                    }

                    // Fetch JSON array for the table. Ask server to order by id ascending when possible.
                    return@withContext try {
                        // Prefer server-side ordering; PostgREST/Supabase supports order=column.asc
                        val path = "$requestedTable?order=id.asc.nullslast"
                        var jsonArray = com.example.tareamov.service.SupabaseClient.fetchTableJson(path)

                        // Defensive: if server doesn't return sorted results or id is missing, sort client-side by 'id'
                        jsonArray = sortJsonArrayById(jsonArray)

                        formatTableJsonResponse(requestedTable, jsonArray)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching table $requestedTable from Supabase", e)
                        "Error al obtener datos de la tabla '$requestedTable' desde Supabase: ${e.message}"
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Table-detection shortcut failed", t)
            }
            
            // First try with RAG service for better context understanding
            val ragResponse = ragService.processRAGQuery(query)
            
            Log.d(TAG, "RAG Response Length: ${ragResponse.length} characters")
            Log.d(TAG, "RAG Response Content: $ragResponse")
            
            // If RAG provides a good response, use it
            if (ragResponse.isNotBlank() && !ragResponse.startsWith("Error")) {
                Log.d(TAG, "RAG service provided response - SUCCESS")
                Log.d(TAG, "============================")
                return@withContext ragResponse
            }
            
            Log.d(TAG, "RAG failed, trying traditional approach with enhanced prompt...")
            
            // Fallback to traditional approach with enhanced prompt
            val enhancedPrompt = buildEnhancedPrompt(query)
            Log.d(TAG, "Enhanced Prompt Length: ${enhancedPrompt.length} characters")
            Log.d(TAG, "Enhanced Prompt Content: $enhancedPrompt")
            
            val rawResponse = try {
                mspClient.sendPrompt(enhancedPrompt, includeHistory = true, includeDatabaseContext = true)
            } catch (e: Exception) {
                Log.w(TAG, "MSPClient threw while sending enhanced prompt", e)
                "Error: ${e.message}"
            }

            Log.d(TAG, "MSP Client raw response preview: ${rawResponse.take(200)}")

            // Detect explicit error responses from MSPClient
            if (rawResponse.isBlank() || rawResponse.startsWith("Error:") || rawResponse.contains("No se pudo conectar", ignoreCase = true)) {
                Log.w(TAG, "MSPClient unavailable or returned error. Falling back to LocalLlama or direct response.")
                try {
                    val local = LocalLlamaService(context).generateResponse(enhancedPrompt)
                    if (local.isNotBlank()) return@withContext local
                } catch (e: Exception) {
                    Log.e(TAG, "LocalLlama also failed", e)
                }

                // As last resort, return a helpful error message
                return@withContext "No se pudo procesar la consulta con el servidor LLM. Aquí hay datos crudos disponibles o intenta de nuevo más tarde."
            }

            rawResponse
        } catch (e: Exception) {
            Log.e(TAG, "Error processing query", e)
            "Error al procesar la consulta: ${e.message ?: "Error desconocido"}"
        }
    }
    
    /**
     * Get available MCP tools
     */
    fun getAvailableMCPTools(): List<MCPToolService.MCPTool> {
        return mcpToolService.getAvailableTools()
    }
    
    /**
     * Execute MCP tool directly
     * This method allows the UI to execute tools without going through the LLM
     */
    suspend fun executeMCPTool(toolName: String, arguments: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔧 Executing MCP tool: $toolName")
            Log.d(TAG, "Arguments: $arguments")
            
            val result = mcpToolService.executeTool(toolName, arguments)
            
            // Format the result for display
            val formattedResult = mcpToolService.formatToolResult(result, toolName)
            
            Log.d(TAG, if (result.success) "✅ Tool executed successfully" else "❌ Tool execution failed")
            
            return@withContext formattedResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing MCP tool", e)
            return@withContext "❌ Error ejecutando herramienta MCP: ${e.message}"
        }
    }
    
    /**
     * Process query with MCP tools available
     * The LLM will automatically use tools when needed
     */
    suspend fun processQueryWithMCP(query: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔧 Processing query with MCP tools: $query")
            
            // Use DatabaseQueryService which now has MCP integration
            val result = databaseQueryService.processQueryWithMCP(query)
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in MCP query processing", e)
            return@withContext "Error procesando consulta con MCP: ${e.message}"
        }
    }

    /**
     * Detects simple phrases asking for "todos los datos" or "dame todos" of a table.
     * Returns the normalized table name if detected, otherwise null.
     */
    private fun detectTableFetchRequest(query: String): String? {
        try {
            val q = query.lowercase().trim()
            // pattern examples: "dame todos los datos de la tabla videos", "mostrar tabla personas", "ver todos usuarios"
            val patterns = listOf("tabla", "tabla de", "tabla:", "de la tabla", "de la tabla ")

            // check common Spanish request starters
            val starters = listOf("dame todos", "dame todos los datos", "mostrar", "mostrar todos", "ver todos", "ver todos los datos", "listar", "lista de", "muéstrame", "muestrame")

            val containsStarter = starters.any { q.contains(it) }
            if (!containsStarter && !q.contains("tabla")) return null

            // attempt to extract the word after 'tabla' or after starter 'de la tabla' etc.
            // Look for 'tabla X' or 'tabla de X' or 'de la tabla X'
            val tableRegex = Regex("tabla(?: de|:)?\\s+([a-z0-9_]+)", RegexOption.IGNORE_CASE)
            var match = tableRegex.find(q)
            if (match != null) {
                return match.groupValues[1].lowercase()
            }

            val simpleRegex = Regex("(?:dame todos(?: los datos)? de )([a-z0-9_]+)", RegexOption.IGNORE_CASE)
            match = simpleRegex.find(q)
            if (match != null) return match.groupValues[1].lowercase()

            // fallback: find known table names in query by scanning expected table list
            val knownTables = listOf(
                "personas", "usuarios", "videos", "topics", "content_items", "tasks",
                "subscriptions", "task_submissions", "chat_messages", "file_contexts", "courses",
                "roles", "recursos", "rol_recursos", "contentitems", "contentitems"
            )
            for (t in knownTables) {
                if (q.contains(t)) return t
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectTableFetchRequest error", e)
        }
        return null
    }

    // Data class for field-by-id extraction
    private data class FieldById(val table: String, val id: Long, val field: String?)

    /**
     * Detect expressions like:
     *  - "dame el title del video con id=55"
     *  - "¿Cuál es el titulo del video id 55?"
     *  - "dame el creator_username del id 11 de la tabla courses"
     * Returns FieldById(table, id, field) or null
     */
    private fun detectFieldByIdRequest(query: String): FieldById? {
        try {
            val q = query.lowercase()
            
            // First try pattern: "field del id X de la tabla Y" (explicit table mention)
            val regexExplicitTable = Regex("([a-z0-9_áéíóúñ ]+?)\\s+del?\\s+id\\s*(?:=|:)?\\s*([0-9]+)\\s+de\\s+(?:la |el )?tabla\\s+([a-z0-9_]+)", RegexOption.IGNORE_CASE)
            val mExplicit = regexExplicitTable.find(q)
            if (mExplicit != null) {
                val rawField = mExplicit.groupValues[1].trim()
                val normalizedField = normalizeRequestedField(rawField)
                val id = mExplicit.groupValues[2].toLongOrNull() ?: return null
                val rawTable = mExplicit.groupValues[3].trim().lowercase()
                val table = mapTableName(rawTable)
                Log.d(TAG, "detectFieldById matched (explicit tabla): rawField='${rawField}', normalizedField='${normalizedField}', table='$table', id=$id")
                return FieldById(table, id, normalizedField)
            }
            
            // Original pattern: 'field del table con id=123' or 'field del table id 123'
            val regex = Regex("([a-z0-9_áéíóúñ ]+?) del? (?:la |el )?([a-z0-9_]+) (?:con )?id\\s*(?:=|:)?\\s*([0-9]+)", RegexOption.IGNORE_CASE)
            val m = regex.find(q)
            if (m != null) {
                val rawField = m.groupValues[1].trim()
                val normalizedField = normalizeRequestedField(rawField)
                val rawTable = m.groupValues[2].trim().lowercase()
                val table = mapTableName(rawTable)
                val id = m.groupValues[3].toLongOrNull() ?: return null
                Log.d(TAG, "detectFieldById matched: rawField='${rawField}', normalizedField='${normalizedField}', table='$table', id=$id")
                return FieldById(table, id, normalizedField)
            }

            // Another pattern: 'title of video with id 55' or 'title video id 55'
            val regex2 = Regex("([a-z0-9_áéíóúñ ]+?) (?:de |del | del)?([a-z0-9_]+) id\\s*(?:=|:)?\\s*([0-9]+)", RegexOption.IGNORE_CASE)
            val m2 = regex2.find(q)
            if (m2 != null) {
                val rawField = m2.groupValues[1].trim()
                val normalizedField = normalizeRequestedField(rawField)
                val rawTable = m2.groupValues[2].trim().lowercase()
                val table = mapTableName(rawTable)
                val id = m2.groupValues[3].toLongOrNull() ?: return null
                Log.d(TAG, "detectFieldById matched (alt): rawField='${rawField}', normalizedField='${normalizedField}', table='$table', id=$id")
                return FieldById(table, id, normalizedField)
            }

            return null
        } catch (e: Exception) {
            Log.w(TAG, "detectFieldByIdRequest error", e)
            return null
        }
    }

    // Map common Spanish (or user-friendly) table names to the actual database table names
    // e.g. 'tarea' or 'tareas' -> 'tasks', 'video' -> 'videos', 'curso' -> 'courses'
    private fun mapTableName(raw: String): String {
        try {
            val r = raw.lowercase().trim()
            val mapping = mapOf(
                "tarea" to "tasks",
                "tareas" to "tasks",
                "video" to "videos",
                "videos" to "videos",
                "usuario" to "usuarios",
                "usuarios" to "usuarios",
                "persona" to "personas",
                "personas" to "personas",
                "curso" to "courses",
                "cursos" to "courses",
                "rol" to "roles",
                "roles" to "roles",
                "recurso" to "recursos",
                "recursos" to "recursos",
                "tema" to "topics",
                "temas" to "topics",
                "topic" to "topics",
                "topics" to "topics",
                "contentitem" to "content_items",
                "content_items" to "content_items",
                "task_submission" to "task_submissions",
                "task_submissions" to "task_submissions"
            )

            if (mapping.containsKey(r)) return mapping[r]!!

            // If already matches known table names used in DB, return as-is
            val known = listOf(
                "personas", "usuarios", "videos", "topics", "content_items", "tasks",
                "subscriptions", "task_submissions", "chat_messages", "file_contexts", "courses",
                "roles", "recursos", "rol_recursos"
            )
            if (known.contains(r)) return r

            // Try simple English pluralization fallback: if Spanish looks singular, attempt to map to english plural
            when (r) {
                "tarea" -> return "tasks"
            }

            // Default: if it ends with 's' assume plural; otherwise add 's'
            return if (r.endsWith("s")) r else r + "s"
        } catch (e: Exception) {
            Log.w(TAG, "mapTableName failed for $raw", e)
            return raw
        }
    }

    /**
     * Try to extract a field from a JsonObject by multiple likely names
     */
    private fun extractFieldFromJson(obj: com.google.gson.JsonObject, field: String): String? {
        try {
            val candidates = listOf(field, field.lowercase(), field.replace("_", ""), field.replace("_", " "), field.replace(" ", "_"))
            for (c in candidates) {
                if (obj.has(c) && !obj.get(c).isJsonNull) {
                    val v = obj.get(c)
                    return if (v.isJsonPrimitive) v.asString else v.toString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFieldFromJson error", e)
        }
        return null
    }

    // Normalize a user-requested field phrase into a likely JSON key: remove Spanish stopwords/verbs and punctuation
    private fun normalizeRequestedField(raw: String): String {
        try {
            var s = raw.lowercase().trim()
            // Remove leading polite verbs or phrases like 'dame','muéstrame','muestrame','mostrar','por favor'
            s = s.replace(Regex("^(dame|muéstrame|muestrame|mostrar|por favor|porfa)\\b\\s*" , RegexOption.IGNORE_CASE), "")
            // Remove occurrences of articles/prepositions that can appear around field names
            s = s.replace(Regex("\\b(de|del|la|el|los|las|con|al|para)\\b", RegexOption.IGNORE_CASE), " ")
            // Remove punctuation
            s = s.replace(Regex("[^a-z0-9_ ]"), " ")
            // Collapse spaces and convert to underscore-delimited key
            s = s.replace(Regex("\\s+"), " ").trim().replace(" ", "_")
            // Trim leading/trailing underscores
            s = s.trim('_')
            if (s.isEmpty()) return raw.lowercase().replace(Regex("[^a-z0-9_]"), "_")
            return s
        } catch (e: Exception) {
            Log.w(TAG, "normalizeRequestedField failed for '$raw'", e)
            return raw.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        }
    }

    /**
     * Formats the Supabase JsonArray response into a human-friendly trimmed JSON string.
     * Caps rows to 100 and total size to avoid huge payloads.
     */
    private fun formatTableJsonResponse(table: String, jsonArray: com.google.gson.JsonArray): String {
        return try {
            val maxRows = 100
            val rows = jsonArray.take(maxRows)
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val limited = com.google.gson.JsonArray()
            for (el in rows) limited.add(el)

            var out = "Tabla: $table\nFilas mostradas: ${rows.size}"
            if (jsonArray.size() > maxRows) out += " (mostrando primeros $maxRows filas)"
            out += "\n\n"
            out += gson.toJson(limited)

            // cap output size
            val maxSize = 200 * 1024 // 200KB
            if (out.length > maxSize) {
                out = out.take(maxSize) + "\n... (salida truncada por tamaño)"
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "formatTableJsonResponse error", e)
            "Error formateando la respuesta de la tabla '$table': ${e.message}"
        }
    }

    /**
     * Sorts a JsonArray by numeric 'id' field ascending when possible. Returns a new JsonArray.
     */
    private fun sortJsonArrayById(arr: com.google.gson.JsonArray): com.google.gson.JsonArray {
        return try {
            val list = arr.mapNotNull { el ->
                try {
                    val obj = el.asJsonObject
                    val idEl = obj.get("id")
                    val idNum = when {
                        idEl == null || idEl.isJsonNull -> null
                        idEl.isJsonPrimitive && idEl.asJsonPrimitive.isNumber -> idEl.asLong
                        idEl.isJsonPrimitive -> idEl.asString.toLongOrNull()
                        else -> null
                    }
                    Pair(idNum, obj)
                } catch (e: Exception) {
                    null
                }
            }.sortedWith(compareBy<Pair<Long?, com.google.gson.JsonObject>> { it.first ?: Long.MAX_VALUE })

            val out = com.google.gson.JsonArray()
            for ((_, obj) in list) out.add(obj)
            // include any elements that couldn't be parsed (append at end)
            val parsed = list.map { it.second }
            arr.forEach { if (it.isJsonObject && parsed.none { p -> p == it.asJsonObject }) out.add(it) }
            out
        } catch (e: Exception) {
            Log.w(TAG, "sortJsonArrayById failed", e)
            arr
        }
    }
    
    /**
     * Build enhanced prompt for better LLM understanding
     */
    private fun buildEnhancedPrompt(query: String): String {
        return try {
            val dbService = DatabaseQueryService(context)
            val jsonStr = runBlocking { dbService.generateDatabaseJson() }
            val json = JSONObject(jsonStr)

            val tableNames = json.keys().asSequence().filter { it != "schema" && it != "statistics" }.toList()
            val tableList = tableNames.mapIndexed { i, name -> "${i + 1}. $name" }.joinToString("\n")

            """
        Eres un asistente experto en bases de datos para TareaMov, una plataforma educativa Android.

        BASE DE DATOS DISPONIBLE - ${tableNames.size} TABLAS:
$tableList

        CAPACIDADES:
        - Consultas directas a tablas (SELECT * FROM tabla)
        - Análisis manual de datos (Divide y Vencerás)
        - ⛔ NO SOPORTA: JOINs, GROUP BY o Consultas Complejas (Modo Strict)
        - Generación de gráficos avanzados
        - Búsquedas específicas y filtrados simples

        TIPOS DE GRÁFICOS DISPONIBLES:
        - GRAPH_REQUEST:USER_VIDEOS - Usuarios con más videos
        - GRAPH_REQUEST:TOPIC_CONTENT - Contenido por tema
        - GRAPH_REQUEST:COURSE_TOPICS - Temas por curso
        - GRAPH_REQUEST:TASKS_TOPICS - Tareas por tema
        - GRAPH_REQUEST:SUBSCRIPTIONS - Análisis de suscripciones
        - GRAPH_REQUEST:ROLES_RECURSOS - Análisis de roles y recursos

        INSTRUCCIONES:
        1. Tienes acceso completo a las tablas listadas arriba
        2. Si la consulta pide un gráfico, responde con GRAPH_REQUEST:[TIPO]
        3. Para datos específicos, proporciona información concisa y estructurada
        4. Para listas, limita a elementos más relevantes
        5. Para conteos, da números específicos de cualquier tabla
        6. Si no hay datos, indícalo claramente
        7. Puedes hacer consultas complejas que involucren múltiples tablas

        CONSULTA DEL USUARIO: $query

        RESPUESTA:
        """.trimIndent()
        } catch (e: Exception) {
            // Fallback to original static prompt if generation fails
            return """
        Eres un asistente experto en bases de datos para TareaMov, una plataforma educativa Android.

        BASE DE DATOS DISPONIBLE - 14 TABLAS:
        1. personas - Información personal de usuarios
        2. usuarios - Cuentas de usuario para autenticación
        3. videos - Videos educativos y contenido multimedia
        4. topics - Temas organizacionales para agrupar contenido
        5. content_items - Elementos de contenido organizados por temas
        6. tasks - Tareas asociadas a temas específicos
        7. subscriptions - Relaciones de suscripción entre usuarios
        8. task_submissions - Entregas de tareas por parte de usuarios
        9. chat_messages - Mensajes del chat del sistema
        10. file_contexts - Contextos de archivos subidos al sistema
        11. courses - Cursos estructurados con contenido educativo
        12. roles - Roles y permisos del sistema
        13. recursos - Recursos disponibles en el sistema
        14. rol_recursos - Relación entre roles y recursos con permisos

        RESPUESTA:
        """.trimIndent()
        }
    }

    /**
     * Convierte un archivo a formato JSON usando el servidor MCP
     *
     * @param uri URI del archivo a convertir
     * @param fileName Nombre del archivo
     * @return FileContext con el contenido JSON si tuvo éxito, o error en caso contrario
     */
    suspend fun convertFileToJson(uri: Uri, fileName: String): FileContext = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Iniciando conversión del archivo: $fileName (URI: $uri)")
            
            // Verificar si es un URI de Google Drive
            val isGoogleDriveUri = isGoogleDriveUri(uri)
            if (isGoogleDriveUri) {
                Log.d(TAG, "📱 Detectado archivo de Google Drive, usando manejo especial")
                return@withContext handleGoogleDriveFile(uri, fileName)
            }
            
            // Usar el nuevo servicio de conversión de archivos para el procesamiento
            Log.d(TAG, "🔄 Usando FileConverterService para procesar el archivo")
            return@withContext fileConverterService.convertFileToStructuredJson(uri, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al convertir archivo a JSON: ${e.message}", e)
            return@withContext createErrorFileContext(
                fileName,
                "Error al procesar el archivo: ${e.message}"
            )
        }
    }
    
    /**
     * Verifica si un URI pertenece a Google Drive
     */
    /**
     * Verifica si un URI pertenece a Google Drive u otro servicio en la nube
     * Esta función detecta URIs de servicios en la nube como Google Drive, Docs, etc.
     */
    fun isGoogleDriveUri(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        val uriString = uri.toString().lowercase()
        
        return authority.contains("google") || 
               authority.contains("docs") || 
               authority.contains("drive") ||
               uriString.contains("docs.google.com") ||
               uriString.contains("drive.google.com") ||
               uriString.contains("content://com.google.android") ||
               uriString.contains("content://com.google.apps")
    }

    /**
     * Intenta acceder directamente al archivo sin usar APIs de Google Drive
     * Este método evita problemas de autenticación con Google Play Services
     */
    private suspend fun tryDirectFileAccess(uri: Uri, fileName: String): FileContext? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Intentando acceso directo al archivo: $fileName")
            
            // Intentar crear archivo temporal directamente desde el URI
            val tempFile = createTempFileFromUri(uri, fileName)
            if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                Log.d(TAG, "✅ Acceso directo exitoso, archivo temporal creado: ${tempFile.length()} bytes")
                
                // Verificar conexión MCP
                if (!testMCPServerConnection()) {
                    tempFile.delete()
                    Log.e(TAG, "❌ Servidor MCP no disponible")
                    return@withContext createErrorFileContext(
                        fileName,
                        "Servidor MCP no disponible. Por favor intenta más tarde."
                    )
                }
                
                // Procesar archivo con MCP
                val result = processFileWithMCP(tempFile, fileName)
                tempFile.delete() // Limpiar archivo temporal
                return@withContext result
            } else {
                Log.d(TAG, "❌ No se pudo acceder directamente al archivo")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ Error en acceso directo: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Procesa un archivo con el servidor MCP
     */
    private suspend fun processFileWithMCP(file: File, fileName: String): FileContext = withContext(Dispatchers.IO) {
        try {
            val mimeType = getMimeTypeFromFileName(fileName)
            
            // Crear solicitud multipart
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", 
                    fileName,
                    file.asRequestBody(mimeType.toMediaTypeOrNull())
                )
                .build()
            
            // Intentar con múltiples URLs
            val allUrls = listOf(MCP_SERVER_URL) + FALLBACK_URLS
            var lastError: Exception? = null
            
            for (url in allUrls) {
                try {
                    Log.d(TAG, "🔄 Intentando conversión con URL: $url")
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()
                    
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            Log.d(TAG, "✅ Archivo convertido exitosamente con $url")
                            
                            return@withContext FileContext(
                                id = 0,
                                submissionId = -1,
                                fileName = fileName,
                                fileType = mimeType,
                                fileContent = responseBody,
                                extractedText = responseBody,
                                metadata = "Convertido por MCP Server ($url)"
                            )
                        } else {
                            Log.e(TAG, "❌ Error en respuesta del servidor: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error conectando a $url: ${e.message}")
                    lastError = e
                }
            }
            
            throw lastError ?: Exception("No se pudo conectar con ningún servidor MCP")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando archivo con MCP", e)
            return@withContext createErrorFileContext(
                fileName,
                "Error al procesar archivo: ${e.message}"
            )
        }
    }
    
    /**
     * Maneja específicamente archivos de Google Drive
     * Usa acceso directo sin APIs de Google para evitar problemas de autenticación
     */
    private suspend fun handleGoogleDriveFile(uri: Uri, fileName: String): FileContext = withContext(Dispatchers.IO) {
        Log.d(TAG, "📑 Procesando archivo de Google Drive: $fileName")
        
        // Primero intentar acceso directo sin usar APIs de Google
        val directAccessResult = tryDirectFileAccess(uri, fileName)
        if (directAccessResult != null) {
            Log.d(TAG, "✅ Archivo procesado exitosamente mediante acceso directo")
            return@withContext directAccessResult
        }
        
        // Si el acceso directo no funciona, intentar con método alternativo
        Log.d(TAG, "⚠️ Acceso directo falló, intentando método alternativo")
        
        try {
            // Intentar exportar/descargar el archivo (sin usar Google Drive API)
            val exportResult = tryExportGoogleDriveFile(uri, fileName)
            
            if (exportResult != null) {
                val (tempFile, mimeType) = exportResult
                Log.d(TAG, "✅ Archivo de Google Drive exportado exitosamente: ${tempFile.name}")
                
                try {
                    // Verificar si el servidor MCP está disponible
                    if (!testMCPServerConnection()) {
                        tempFile.delete()
                        Log.e(TAG, "❌ Servidor MCP no disponible")
                        return@withContext createErrorFileContext(
                            fileName,
                            "Servidor MCP no disponible. Por favor intenta más tarde."
                        )
                    }
                    
                    // Procesar archivo con MCP
                    val result = processFileWithMCP(tempFile, fileName)
                    return@withContext result
                } finally {
                    // Asegurarnos de limpiar el archivo temporal
                    tempFile.delete()
                }
            }
            
            // Si llegamos aquí, no se pudo acceder al archivo de ninguna manera
            Log.w(TAG, "⚠️ No se pudo acceder al archivo de Google Drive por ningún método")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error procesando archivo de Google Drive: ${e.message}", e)
        }
        
        // Devolver respuesta informativa para el usuario
        return@withContext createGoogleDriveInstructionsContext(uri, fileName)
    }

    /**
     * Crea un contexto con instrucciones para archivos de Google Drive
     */
    private fun createGoogleDriveInstructionsContext(uri: Uri, fileName: String): FileContext {
        val jsonContent = """
        {
            "fileType": "google_drive",
            "fileName": "$fileName",
            "uri": "${uri.toString()}",
            "accessMethod": "instructions",
            "message": "Archivo de Google Drive detectado",
            "instructions": {
                "title": "Cómo acceder a archivos de Google Drive",
                "steps": [
                    "1. Abre la aplicación Google Drive en tu dispositivo",
                    "2. Encuentra el archivo '$fileName'",
                    "3. Toca el menú de tres puntos (...) junto al archivo",
                    "4. Selecciona 'Descargar' o 'Hacer disponible sin conexión'",
                    "5. Una vez descargado, regresa a esta aplicación",
                    "6. Usa el selector de archivos para elegir el archivo desde tu almacenamiento local",
                    "7. El archivo ahora se procesará correctamente"
                ],
                "alternative_methods": [
                    "Compartir el archivo con 'Cualquier persona con el enlace'",
                    "Usar la opción 'Abrir con' desde Google Drive",
                    "Copiar el archivo a tu almacenamiento local"
                ]
            },
            "fileId": "${extractGoogleDriveFileId(uri)}",
            "timestamp": "${System.currentTimeMillis()}",
            "supportedFormats": [
                "PDF", "DOC", "DOCX", "PPT", "PPTX", "XLS", "XLSX", "TXT", "RTF"
            ]
        }
        """.trimIndent()
        
        return FileContext(
            id = 0,
            submissionId = -1,
            fileName = fileName,
            fileType = "google_drive_instructions",
            fileContent = jsonContent,
            extractedText = "Para procesar este archivo de Google Drive, necesitas descargarlo primero a tu dispositivo. Sigue las instrucciones proporcionadas para acceder al archivo.",
            metadata = "Instrucciones para acceso a Google Drive"
        )
    }
    
    /**
     * Extrae el ID del archivo de Google Drive a partir de su URI
     */
    private fun extractGoogleDriveFileId(uri: Uri): String {
        val uriString = uri.toString()
        return when {
            uriString.contains("id=") -> {
                val start = uriString.indexOf("id=") + 3
                val end = uriString.indexOf("&", start).takeIf { it > 0 } ?: uriString.length
                uriString.substring(start, end)
            }
            uriString.contains("/d/") -> {
                val start = uriString.indexOf("/d/") + 3
                val end = uriString.indexOf("/", start).takeIf { it > 0 } ?: uriString.length
                uriString.substring(start, end)
            }
            else -> "unknown_id"
        }
    }
    
    /**
     * Crea un FileContext con información de error
     */
    private fun createErrorFileContext(fileName: String, errorMessage: String): FileContext {
        return FileContext(
            id = 0,
            submissionId = -1,
            fileName = fileName,
            fileType = getMimeTypeFromFileName(fileName),
            fileContent = createErrorJson(fileName, errorMessage),
            extractedText = errorMessage,
            metadata = "Error: $errorMessage"
        )
    }
    
    /**
     * Crea un JSON con información de error
     */
    private fun createErrorJson(fileName: String, errorMessage: String): String {
        val fileType = getMimeTypeFromFileName(fileName)
        val isSupported = isSupportedFileType(fileName)
        val typeInstructions = getFileTypeInstructions(fileName)
        
        return """
        {
            "error": true,
            "fileName": "$fileName",
            "fileType": "$fileType",
            "isSupported": $isSupported,
            "message": "$errorMessage",
            "timestamp": "${System.currentTimeMillis()}",
            "typeInstructions": ${JSONObject.quote(typeInstructions)},
            "suggestions": [
                "Si es un archivo de Google Drive, descárgalo primero a tu dispositivo",
                "Verifica que tienes permiso para acceder al archivo",
                "Asegúrate de que el servidor MCP esté ejecutándose correctamente"
                ${if (!isSupported) ", ${JSONObject.quote("Convierte el archivo a un formato soportado (PDF, DOCX, TXT, etc.)")}" else ""}${if (fileType.contains("powerpoint")) ", ${JSONObject.quote("Si es una presentación, considera exportarla como PDF")}" else ""}${if (fileType.startsWith("image")) ", ${JSONObject.quote("Si es una imagen con texto, asegúrate de que sea legible")}" else ""}
            ]
        }
        """.trimIndent()
    }
    
    /**
     * Crea un archivo temporal a partir de una URI
     * Maneja diferentes tipos de URIs incluyendo archivos de Google Drive
     */
    private fun createTempFileFromUri(uri: Uri, fileName: String): File? {
        return try {
            Log.d(TAG, "🔄 Creando archivo temporal desde URI: ${uri.toString()}")
            
            // Intentar obtener InputStream del URI
            val inputStream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                Log.w(TAG, "❌ Permisos denegados para URI: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "❌ Error abriendo InputStream: ${e.message}")
                null
            }
            
            if (inputStream == null) {
                Log.e(TAG, "❌ No se pudo obtener InputStream del URI")
                return null
            }
            
            // Crear archivo temporal con extensión correcta
            val fileExtension = getFileExtension(fileName)
            val tempFile = File.createTempFile(
                "mcp_${System.currentTimeMillis()}_", 
                fileExtension,
                context.cacheDir // Usar cache directory para archivos temporales
            )
            
            // Copiar contenido del InputStream al archivo temporal
            var bytesWritten = 0L
            tempFile.outputStream().use { outputStream ->
                val buffer = ByteArray(8192) // Buffer de 8KB
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesWritten += bytesRead
                }
            }
            inputStream.close()
            
            // Verificar que el archivo se creó correctamente
            if (tempFile.exists() && tempFile.length() > 0) {
                Log.d(TAG, "✅ Archivo temporal creado exitosamente: ${tempFile.name} (${bytesWritten} bytes)")
                return tempFile
            } else {
                Log.e(TAG, "❌ El archivo temporal está vacío o no se creó correctamente")
                tempFile.delete()
                return null
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Error de seguridad creando archivo temporal: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creando archivo temporal: ${e.message}", e)
            null
        }
    }
    
    /**
     * Obtiene la extensión de un archivo a partir de su nombre
     */
    private fun getFileExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0) {
            fileName.substring(dotIndex)
        } else {
            ""
        }
    }
    
    /**
     * Determina el tipo MIME basado en la extensión del archivo
     */
    private fun getMimeTypeFromFileName(fileName: String): String {
        return when {
            // Documentos de Office
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".doc", ignoreCase = true) -> "application/msword"
            fileName.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            fileName.endsWith(".ppt", ignoreCase = true) -> "application/vnd.ms-powerpoint"
            fileName.endsWith(".pptx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            fileName.endsWith(".xls", ignoreCase = true) -> "application/vnd.ms-excel"
            fileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            fileName.endsWith(".odt", ignoreCase = true) -> "application/vnd.oasis.opendocument.text"
            fileName.endsWith(".ods", ignoreCase = true) -> "application/vnd.oasis.opendocument.spreadsheet"
            fileName.endsWith(".odp", ignoreCase = true) -> "application/vnd.oasis.opendocument.presentation"
            
            // Archivos de texto y código
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            fileName.endsWith(".java", ignoreCase = true) -> "text/x-java"
            fileName.endsWith(".kt", ignoreCase = true) -> "text/x-kotlin"
            fileName.endsWith(".json", ignoreCase = true) -> "application/json"
            fileName.endsWith(".xml", ignoreCase = true) -> "application/xml"
            fileName.endsWith(".html", ignoreCase = true) -> "text/html"
            fileName.endsWith(".css", ignoreCase = true) -> "text/css"
            fileName.endsWith(".js", ignoreCase = true) -> "application/javascript"
            fileName.endsWith(".py", ignoreCase = true) -> "text/x-python"
            fileName.endsWith(".cpp", ignoreCase = true) -> "text/x-c++src"
            fileName.endsWith(".cs", ignoreCase = true) -> "text/x-csharp"
            
            // Imágenes
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            fileName.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            
            // Audio y Video
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            fileName.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            
            // Archivos comprimidos
            fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            fileName.endsWith(".rar", ignoreCase = true) -> "application/x-rar-compressed"
            fileName.endsWith(".7z", ignoreCase = true) -> "application/x-7z-compressed"
            fileName.endsWith(".tar", ignoreCase = true) -> "application/x-tar"
            fileName.endsWith(".gz", ignoreCase = true) -> "application/gzip"
            
            else -> "application/octet-stream"
        }
    }

    /**
     * Verifica si el tipo de archivo es soportado por el MCP
     */
    private fun isSupportedFileType(fileName: String): Boolean {
        val mimeType = getMimeTypeFromFileName(fileName)
        return when {
            // Documentos de texto y código - Altamente soportados
            mimeType.startsWith("text/") -> true
            mimeType == "application/pdf" -> true
            mimeType == "application/json" -> true
            mimeType == "application/xml" -> true
            
            // Documentos de Office - Soporte variable
            mimeType.contains("officedocument") || 
            mimeType.contains("msword") ||
            mimeType.contains("ms-excel") ||
            mimeType.contains("ms-powerpoint") ||
            mimeType.contains("opendocument") -> true // Asumimos que el MCP los soporta
            
            // Imágenes - Soporte limitado (OCR necesario)
            mimeType.startsWith("image/") -> true // El MCP debería tener OCR
            
            // Audio y Video - No soportados directamente
            mimeType.startsWith("audio/") || 
            mimeType.startsWith("video/") -> false
            
            // Archivos comprimidos - No soportados
            mimeType.contains("zip") ||
            mimeType.contains("rar") ||
            mimeType.contains("7z") ||
            mimeType.contains("tar") ||
            mimeType.contains("gzip") -> false
            
            else -> false
        }
    }

    /**
     * Obtiene instrucciones específicas para el tipo de archivo
     */
    private fun getFileTypeInstructions(fileName: String): String {
        val mimeType = getMimeTypeFromFileName(fileName)
        return when {
            // PowerPoint específicamente
            mimeType.contains("powerpoint") || fileName.endsWith(".ppt", ignoreCase = true) || 
            fileName.endsWith(".pptx", ignoreCase = true) -> """
                Este es un archivo de PowerPoint ($mimeType).
                
                    "Para asegurar la mejor compatibilidad:",
                    "1. Si el archivo está en Google Drive:",
                    "   - Descarga el archivo completamente a tu dispositivo",
                    "   - O usa 'Hacer disponible sin conexión' en la app de Drive",
                    "2. Si tienes problemas de acceso:",
                    "   - Descarga el archivo completamente antes de subirlo",
                    "   - Verifica que no esté protegido o bloqueado",
                    "3. Alternativas si persisten los problemas:",
                    "   - Exporta la presentación como PDF",
                    "   - Guarda las diapositivas como imágenes PNG",
                    "   - Copia el texto a un documento de texto"
                """.trimIndent()
            
            // Archivos no soportados
            !isSupportedFileType(fileName) -> """
                Este tipo de archivo ($mimeType) no es directamente soportado por el análisis.
                
                Sugerencias:
                1. Para archivos de audio/video: Proporciona una transcripción en formato texto
                2. Para archivos comprimidos: Extrae y sube los archivos individualmente
                3. Si es otro tipo de archivo, conviértelo a un formato soportado:
                   - Documentos: PDF, DOC, DOCX, TXT
                   - Presentaciones: PPT, PPTX
                   - Hojas de cálculo: XLS, XLSX
                """.trimIndent()
            
            // Archivos de Office
            mimeType.contains("officedocument") || 
            mimeType.contains("msword") ||
            mimeType.contains("ms-excel") -> """
                Este es un documento de Office ($mimeType).
                
                    "Para mejor compatibilidad:",
                    "1. Asegúrate de que el archivo no esté protegido con contraseña",
                    "2. Si es un documento, considera convertirlo a PDF",
                    "3. Si es una hoja de cálculo, considera exportarla como CSV o XLSX",
                    "4. Si el archivo está en Google Drive, descárgalo completamente antes de subirlo"
                """.trimIndent()
            
            // Imágenes
            mimeType.startsWith("image/") -> """
                Este es un archivo de imagen ($mimeType).
                
                    "Para mejor análisis:",
                    "1. Asegúrate de que el texto en la imagen sea claro y legible",
                    "2. Si la imagen contiene texto importante, considera transcribirlo",
                    "3. El sistema usará OCR para extraer texto, pero su precisión puede variar"
                """.trimIndent()
            
            else -> "Archivo de tipo $mimeType. El sistema intentará procesarlo y extraer su contenido."
        }
    }
    
    /**
     * Verifica si el servidor MCP está disponible
     * @return true si el servidor está disponible, false en caso contrario
     */
    suspend fun testMCPServerConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Verificando conexión con servidor MCP principal...")
            val serverUrls = listOf(MCP_SERVER_URL) + FALLBACK_URLS
            val connectionResults = mutableListOf<Pair<String, Boolean>>()
            var anySuccessful = false
            coroutineScope {
                val jobs = serverUrls.map { url ->
                    async {
                        try {
                            val result = tryConnectToMCP(url)
                            connectionResults.add(Pair(url, result))
                            if (result) anySuccessful = true
                            result
                        } catch (e: Exception) {
                            Log.e(TAG, "Error conectando a $url", e)
                            connectionResults.add(Pair(url, false))
                            false
                        }
                    }
                }
                jobs.awaitAll()
            }
            Log.d(TAG, "📊 Resultados de conexión:")
            connectionResults.forEach { (url, success) ->
                Log.d(TAG, "  ${if (success) "✅" else "❌"} $url: ${if (success) "Conectado" else "Fallido"}")
            }
            if (anySuccessful) {
                Log.d(TAG, "✅ Conexión exitosa con al menos un servidor MCP")
                true
            } else {
                Log.e(TAG, "❌ No se pudo conectar a ningún servidor MCP")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verificando conexión con servidor MCP", e)
            false
        }
    }
    
    /**
     * Intenta conectarse a una URL de MCP específica
     */
    private suspend fun tryConnectToMCP(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS) // Timeout reducido para pruebas de conexión
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
                
            val request = Request.Builder()
                .url(url)
                .head()  // Solo verifica si el servidor responde, sin obtener contenido
                .build()
                
            client.newCall(request).execute().use { response ->
                val isSuccessful = response.isSuccessful
                Log.d(TAG, if (isSuccessful) "✅ Conexión exitosa a $url" else "❌ Falló conexión a $url: ${response.code}")
                return@withContext isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error conectando a $url: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Prepara el contexto JSON para ser enviado al modelo de IA
     * Este método formatea el JSON para que sea más adecuado para el modelo
     */
    fun prepareJsonForAIContext(jsonContent: String): String {
        return try {
            val jsonObject = JSONObject(jsonContent)
            
            // Verificar si el contenido es un error
            if (jsonObject.optBoolean("error", false)) {
                val fileName = jsonObject.optString("fileName", "Desconocido")
                val fileType = jsonObject.optString("fileType", "Desconocido")
                val isSupported = jsonObject.optBoolean("isSupported", true)
                val typeInstructions = jsonObject.optString("typeInstructions", "")
                
                // Si es un error de Google Drive
                if (jsonContent.contains("GOOGLE_DRIVE_ACCESS_ERROR") || 
                    jsonContent.contains("google_drive_error")) {
                    return """
                    # INFORMACIÓN DEL ARCHIVO DE GOOGLE DRIVE
                    
                    Este archivo está almacenado en Google Drive y no se puede acceder directamente desde la aplicación.
                    
                    ## Detalles del archivo
                    - Nombre: ${jsonObject.optString("fileName", "Desconocido")}
                    - URI: ${jsonObject.optString("uri", "No disponible")}
                    - ID: ${jsonObject.optString("fileId", "No disponible")}
                    - Tipo: $fileType
                    
                    ## Instrucciones para el usuario
                    1. Abre la app de Google Drive
                    2. Localiza el archivo
                    3. Descarga el archivo a tu dispositivo (menú "..." > "Descargar")
                    4. Vuelve a esta app y sube el archivo desde tu almacenamiento local
                    
                    ${if (!isSupported) """
                    ## Información adicional sobre el tipo de archivo
                    $typeInstructions
                    """ else ""}
                    
                    ## JSON original
                    ```json
                    ${jsonObject.toString(2)}
                    ```
                    """
                }
                
                // Si es un error de tipo de archivo no soportado
                if (!isSupported) {
                    return """
                    # INFORMACIÓN DEL ARCHIVO NO SOPORTADO
                    
                    El archivo que intentas analizar no es directamente compatible con el sistema.
                    
                    ## Detalles del archivo
                    - Nombre: $fileName
                    - Tipo: $fileType
                    
                    ## Información importante
                    $typeInstructions
                    
                    ## Sugerencias
                    ${jsonObject.optJSONArray("suggestions")?.let { suggestions ->
                        (0 until suggestions.length()).joinToString("\n") { index ->
                            "- ${suggestions.getString(index)}"
                        }
                    }}
                    
                    ## Error original
                    ```json
                    ${jsonObject.toString(2)}
                    ```
                    """
                }
            }
            
            // Para contenido normal, intentar formatear el JSON
            "```json\n${jsonObject.toString(2)}\n```"
        } catch (e: Exception) {
            // Si no es un JSON válido, devolver tal cual
            "```\n$jsonContent\n```"
        }
    }

    /**
     * Analyze the intent behind a query
     */
    private fun analyzeQueryIntent(query: String): QueryIntent {
        val normalizedQuery = query.lowercase().trim()

        // More flexible intent detection with fuzzy matching
        return when {
            normalizedQuery.contains("hola") || normalizedQuery.contains("salud") ||
                    normalizedQuery.startsWith("buen") || normalizedQuery == "hi" || normalizedQuery == "hey" -> {
                QueryIntent.GREETING
            }
            normalizedQuery.contains("ayuda") || normalizedQuery.contains("help") ||
                    normalizedQuery.contains("como funciona") || normalizedQuery.contains("qué puedo") -> {
                QueryIntent.HELP
            }
            normalizedQuery.contains("context") || normalizedQuery.contains("anali") ||
                    normalizedQuery.contains("qué está") || normalizedQuery.contains("que esta") -> {
                QueryIntent.CONTEXT_QUERY
            }
            normalizedQuery.contains("estado") || normalizedQuery.contains("status") ||
                    normalizedQuery.contains("servidor") || normalizedQuery.contains("ollama") ||
                    normalizedQuery.contains("llama") || normalizedQuery.contains("servicio") -> {
                QueryIntent.SERVER_STATUS
            }
            normalizedQuery.contains("gráfico") || normalizedQuery.contains("grafico") ||
                    normalizedQuery.contains("visuali") || normalizedQuery.contains("chart") ||
                    normalizedQuery.contains("gráfic") || normalizedQuery.contains("grafic") ||
                    (normalizedQuery.contains("muestra") &&
                            (normalizedQuery.contains("gráfic") || normalizedQuery.contains("grafic") ||
                                    normalizedQuery.contains("visual"))) -> {
                QueryIntent.GRAPH_QUERY
            }
            // For all other queries, we'll let the DatabaseQueryService handle them
            // This ensures we always try to provide a relevant response
            else -> {
                QueryIntent.LLM_QUERY
            }
        }
    }

    private fun updateContext(query: String) {
        val normalizedQuery = query.lowercase().trim()

        // Update context based on query content
        when {
            normalizedQuery.contains("usuarios") -> {
                conversationContext = "usuarios"
            }
            normalizedQuery.contains("personas") -> {
                conversationContext = "personas"
            }
            normalizedQuery.contains("videos") -> {
                conversationContext = "videos"
            }
            normalizedQuery.contains("temas") -> {
                conversationContext = "temas"
            }
            normalizedQuery.contains("contenido") -> {
                conversationContext = "contenido"
            }
        }
    }

    /**
     * Process a graph query and return a special response format that the UI can interpret
     */
    /**
     * Check the status of the Ollama server
     */
    private suspend fun checkServerStatus(): String {
        return try {
            val isRunning = mspClient.isServerRunning()
            if (isRunning) {
                "El servidor local de Ollama está funcionando correctamente."
            } else {
                "El servidor local de Ollama no está accesible. Por favor, asegúrate de que Ollama está ejecutándose en tu PC y que la aplicación puede conectarse a él (10.0.2.2:11435)."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking server status", e)
            "Error al verificar el estado del servidor: ${e.message}"
        }
    }

    /**
     * Gather relevant information from the database to provide context for the LLM
     */
    private suspend fun gatherDatabaseContext(): String = withContext(Dispatchers.IO) {
        val contextBuilder = StringBuilder()

        try {
            // Disambiguate .toList() call to avoid overload ambiguity
            fun <T> ensureList(input: Any?): List<T> = when (input) {
                is List<*> -> input as List<T>
                is Array<*> -> (input as Array<T>).toList()
                is Iterable<*> -> (input as Iterable<T>).toList()
                else -> emptyList()
            }

            val personas = ensureList<com.example.tareamov.data.entity.Persona>(database.personaDao().getAllPersonasList())
            val usuarios = ensureList<com.example.tareamov.data.entity.Usuario>(database.usuarioDao().getAllUsuarios())
            val videos = ensureList<com.example.tareamov.data.entity.VideoData>(database.videoDao().getAllVideos())
            val topics = ensureList<com.example.tareamov.data.entity.Topic>(database.topicDao().getAllTopics())
            val contentItems = ensureList<com.example.tareamov.data.entity.ContentItem>(database.contentItemDao().getAllContentItems())
            // Add Task and Subscription data
            val tasks = ensureList<com.example.tareamov.data.entity.Task>(database.taskDao().getAllTasks())
            val subscriptions = ensureList<com.example.tareamov.data.entity.Subscription>(database.subscriptionDao().getAllSubscriptions())
            val taskSubmissions = ensureList<com.example.tareamov.data.entity.TaskSubmission>(database.taskSubmissionDao().getAllTaskSubmissions())

            val personaCount = personas.size
            val usuarioCount = usuarios.size
            val videoCount = videos.size
            val topicCount = topics.size
            val contentItemCount = contentItems.size
            val taskCount = tasks.size
            val subscriptionCount = subscriptions.size
            val taskSubmissionCount = taskSubmissions.size

            contextBuilder.append("Resumen de la base de datos:\n")
            contextBuilder.append("- $personaCount personas\n")
            contextBuilder.append("- $usuarioCount usuarios\n")
            contextBuilder.append("- $videoCount videos\n")
            contextBuilder.append("- $topicCount temas\n")
            contextBuilder.append("- $contentItemCount elementos de contenido\n")
            contextBuilder.append("- $taskCount tareas\n")
            contextBuilder.append("- $subscriptionCount suscripciones\n")
            contextBuilder.append("- $taskSubmissionCount envíos de tareas\n\n")

            // Add detailed information about each entity type
            if (personas.isNotEmpty()) {
                contextBuilder.append("Ejemplos de personas:\n")
                personas.take(5).forEach { persona ->
                    contextBuilder.append("- ID: ${persona.id}, Nombre: ${persona.nombres} ${persona.apellidos}, Email: ${persona.email}\n")
                }
                contextBuilder.append("\n")
            }

            if (usuarios.isNotEmpty()) {
                contextBuilder.append("Ejemplos de usuarios:\n")
                usuarios.take(5).forEach { usuario ->
                    val persona = database.personaDao().getPersonaById(usuario.persona_id)
                    val personaName = if (persona != null) "${persona.nombres} ${persona.apellidos}" else "Desconocido"
                    contextBuilder.append("- ID: ${usuario.id}, Usuario: ${usuario.usuario}, Persona: $personaName\n")
                }
                contextBuilder.append("\n")
            }

            if (videos.isNotEmpty()) {
                contextBuilder.append("Ejemplos de videos:\n")
                videos.take(5).forEach { video ->
                    contextBuilder.append("- ID: ${video.id}, Título: ${video.title}, Usuario: ${video.username}, Descripción: ${video.description}\n")
                }
                contextBuilder.append("\n")
            }

            if (topics.isNotEmpty()) {
                contextBuilder.append("Ejemplos de temas:\n")
                topics.take(5).forEach { topic ->
                    val video = database.videoDao().getVideoById(topic.courseId)
                    val courseTitle = video?.title ?: "Desconocido"
                    contextBuilder.append("- ID: ${topic.id}, Nombre: ${topic.name}, Curso: $courseTitle, Descripción: ${topic.description}\n")
                }
                contextBuilder.append("\n")
            }

            if (contentItems.isNotEmpty()) {
                contextBuilder.append("Ejemplos de elementos de contenido:\n")
                contentItems.take(5).forEach { item ->
                    contextBuilder.append("- ID: ${item.id}, Nombre: ${item.name}, Tipo: ${item.contentType}, Tema ID: ${item.topicId}\n")
                }
                contextBuilder.append("\n")
            }

            // Add Task examples
            if (tasks.isNotEmpty()) {
                contextBuilder.append("Ejemplos de tareas:\n")
                tasks.take(5).forEach { task ->
                    val topic = database.topicDao().getTopicById(task.topicId)
                    val topicName = topic?.name ?: "Desconocido"
                    val taskDesc = task.description ?: "Sin descripción"
                    contextBuilder.append("- ID: ${task.id}, Nombre: ${task.name}, Tema: $topicName, Descripción: $taskDesc, Orden: ${task.orderIndex}\n")
                }
                contextBuilder.append("\n")
            }

            // Add Subscription examples
            if (subscriptions.isNotEmpty()) {
                contextBuilder.append("Ejemplos de suscripciones:\n")
                subscriptions.take(5).forEach { subscription ->
                    contextBuilder.append("- Suscriptor ID: ${subscription.subscriberId}, Creador ID: ${subscription.creatorId}, Fecha: ${subscription.subscriptionDate}\n")
                }
                contextBuilder.append("\n")
            }

            // Add TaskSubmission examples
                if (taskSubmissions.isNotEmpty()) {
                contextBuilder.append("Ejemplos de envíos de tareas:\n")
                taskSubmissions.take(5).forEach { submission ->
                    val task = database.taskDao().getTaskById(submission.taskId)
                    val topic = task?.let { database.topicDao().getTopicById(it.topicId) }
                    // Resolve studentId -> username when possible
                    val studentName = try {
                        val u = database.usuarioDao().getUsuarioById(submission.studentId)
                        u?.usuario ?: submission.studentId.toString()
                    } catch (e: Exception) {
                        submission.studentId.toString()
                    }
                    val taskName = task?.name ?: "Desconocida"
                    val topicName = topic?.name ?: "Desconocido"
                    val gradeStr = submission.grade ?: "Sin calificar"
                    contextBuilder.append("- ID: ${submission.id}, Estudiante: $studentName, Tarea: $taskName, Tema: $topicName, Calificación: $gradeStr, Archivo: ${submission.fileName}\n")
                }
                contextBuilder.append("\n")
            }

            // Add detailed ContentItem examples
            if (contentItems.isNotEmpty()) {
                contextBuilder.append("Detalles de elementos de contenido (primeros 5):\n")
                contentItems.take(5).forEach { item ->
                    val topic = database.topicDao().getTopicById(item.topicId)
                    val task = item.taskId?.let { database.taskDao().getTaskById(it) }
                    val name = item.name ?: "Sin nombre"
                    val topicName = topic?.name ?: "Ninguno"
                    val taskName = task?.name ?: "Ninguna"
                    val uriPreview = if (item.uriString.length > 30) item.uriString.take(30) + "..." else item.uriString
                    contextBuilder.append("- ID: ${item.id}, Nombre: $name, Tipo: ${item.contentType}, Tema: $topicName, Tarea: $taskName, URI: $uriPreview\n")
                }
                contextBuilder.append("\n")
            }

            // Add information about relationships
            contextBuilder.append("\nRelaciones entre entidades:\n")
            contextBuilder.append("- Cada Usuario está asociado a una Persona (Usuario.persona_id -> Persona.id)\n")
            contextBuilder.append("- Cada Topic está asociado a un Video (Topic.courseId -> Video.id)\n")
            contextBuilder.append("- Cada ContentItem está asociado a un Topic (ContentItem.topicId -> Topic.id)\n")
            contextBuilder.append("- Cada Task está asociado a un Topic (Task.topicId -> Topic.id)\n")
            contextBuilder.append("- Cada Subscription conecta un usuario suscriptor con un usuario creador\n")
            contextBuilder.append("- Cada ContentItem está asociado a un Topic o una Task (ContentItem.topicId -> Topic.id o ContentItem.taskId -> Task.id)\n")
            contextBuilder.append("- Cada TaskSubmission está asociado a una Task (TaskSubmission.taskId -> Task.id) y un estudiante (TaskSubmission.studentId -> usuarios.id -> usuarios.usuario)\n")

        } catch (e: Exception) {
            Log.e(TAG, "Error gathering database context", e)
            contextBuilder.append("Error al recopilar información de la base de datos: ${e.message}")
        }

        return@withContext contextBuilder.toString()
    }

    // Query intents for the MCP system
    enum class QueryIntent {
        DATABASE_QUERY,
        LLM_QUERY,
        CONTEXT_QUERY,
        GREETING,
        HELP,
        SERVER_STATUS,
        GRAPH_QUERY,
        UNKNOWN
    }
    
    /**
     * Intenta exportar un archivo de Google Drive usando solo el Content Resolver
     * Evita usar APIs de Google Drive para prevenir problemas de autenticación
     */
    private suspend fun tryExportGoogleDriveFile(uri: Uri, fileName: String): Pair<File, String>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Intentando exportar archivo de Google Drive sin usar APIs")
            
            // Intentar acceso directo a través del Content Resolver
            val inputStream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                Log.w(TAG, "❌ Permisos denegados para URI: ${e.message}")
                null
            } catch (e: Exception) {
                Log.w(TAG, "❌ Error abriendo InputStream: ${e.message}")
                null
            }
            
            if (inputStream != null) {
                try {
                    // Crear archivo temporal
                    val mimeType = getMimeTypeFromFileName(fileName)
                    val fileExtension = getFileExtension(fileName)
                    val tempFile = File.createTempFile(
                        "gdrive_${System.currentTimeMillis()}_", 
                        fileExtension,
                        context.cacheDir
                    )
                    
                    // Copiar contenido
                    var bytesWritten = 0L
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                        }
                    }
                    
                    if (tempFile.exists() && tempFile.length() > 0) {
                        Log.d(TAG, "✅ Archivo exportado exitosamente: ${tempFile.name} (${bytesWritten} bytes)")
                        return@withContext Pair(tempFile, mimeType)
                    } else {
                        Log.e(TAG, "❌ El archivo exportado está vacío")
                        tempFile.delete()
                        return@withContext null
                    }
                } finally {
                    inputStream.close()
                }
            }
            
            Log.d(TAG, "❌ No se pudo obtener InputStream del URI")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error exportando archivo de Google Drive: ${e.message}", e)
            return@withContext null
        }
    }
}
