package com.example.tareamov.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.tareamov.BuildConfig
import com.example.tareamov.data.entity.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import com.example.tareamov.service.network.FallbackDnsResolver
import com.example.tareamov.service.network.NetworkConnectivityChecker

/**
 * BackendApiService — Servicio centralizado que reemplaza todas las llamadas
 * directas a Supabase (PostgREST) y R2 Storage.
 *
 * Todas las operaciones CRUD pasan ahora por el backend Node.js,
 * que implementa arquitectura hexagonal con JWT auth.
 *
 * Uso:
 *   BackendApiService.initialize(context)
 *   BackendApiService.login(username, password)
 *   val courses = BackendApiService.getCourses()
 * 
 */
object BackendApiService {

    private const val TAG = "BackendApiService"
    private const val PREFS_NAME = "backend_api_prefs"
    private const val KEY_JWT_TOKEN = "jwt_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"

    /** Guards concurrent refresh attempts — only one refresh at a time */
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var isRefreshing = false

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .serializeNulls()
        .create()

    private const val MAX_RETRIES = 2
    private const val INITIAL_BACKOFF_MS = 1000L

    /**
     * OkHttpClient optimizado para conexiones rápidas:
     *   - Timeouts agresivos (conectar: 10s, leer: 20s) para fail-fast
     *   - Connection pool que reutiliza hasta 8 conexiones idle (5 min TTL)
     *   - DNS con fallback DoH + UDP para emuladores con DNS roto
     *   - Retry automático en fallas de conexión
     */
    private val client = OkHttpClient.Builder()
        .dns(FallbackDnsResolver)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var prefs: SharedPreferences

    /** Base URL del backend (resuelto via ServerEndpointResolver o BuildConfig) */
    val baseUrl: String
        get() {
            val url = BuildConfig.BACKEND_URL.ifBlank {
                "https://mcp-backenddeploy-production.up.railway.app"
            }
            return if (url.endsWith("/")) url.dropLast(1) else url
        }

    private val apiBase: String get() = "$baseUrl/api/v1"

    // ─────────────────────────────────────────────────────────
    // Inicialización y Auth
    // ─────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Warmup DNS en background para resolución instantánea en la primera request
        warmupDns()
    }

    /**
     * Pre-resuelve el hostname del backend en background para que
     * la primera llamada HTTP no tenga que esperar resolución DNS.
     * Principio: Fail-fast — si el DNS falla aquí, el FallbackDnsResolver
     * intentará DoH y UDP en la primera request real.
     */
    private fun warmupDns() {
        Thread {
            try {
                val host = android.net.Uri.parse(baseUrl).host ?: return@Thread
                FallbackDnsResolver.lookup(host)
            } catch (_: Exception) { /* non-fatal warmup */ }
        }.start()
    }

    var jwtToken: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_JWT_TOKEN, null) else null
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_JWT_TOKEN, value).apply()
            }
        }

    var currentUserId: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_USER_ID, 0L) else 0L
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putLong(KEY_USER_ID, value).apply()
            }
        }

    var refreshToken: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_REFRESH_TOKEN, null) else null
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()
            }
        }

    val isAuthenticated: Boolean get() = !jwtToken.isNullOrBlank()

    fun logout() {
        jwtToken = null
        refreshToken = null
        currentUserId = 0L
    }

    // ─────────────────────────────────────────────────────────
    // HTTP Helpers
    // ─────────────────────────────────────────────────────────

    private fun authHeaders(): Headers.Builder {
        return Headers.Builder().apply {
            add("Content-Type", "application/json")
            jwtToken?.let { add("Authorization", "Bearer $it") }
        }
    }

    private fun get(path: String): Request {
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .get()
            .build()
    }

    /**
     * GET sin cabecera Authorization — para rutas publicas (public).
     */
    private fun publicGet(path: String): Request {
        return Request.Builder()
            .url("$apiBase$path")
            .header("Content-Type", "application/json")
            .get()
            .build()
    }

    private fun post(path: String, body: Any?): Request {
        val json = if (body != null) gson.toJson(body) else "{}"
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun put(path: String, body: Any?): Request {
        val json = if (body != null) gson.toJson(body) else "{}"
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .put(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun delete(path: String): Request {
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .delete()
            .build()
    }

    private fun parseErrorResponse(bodyStr: String, statusCode: Int): Pair<String, String?> {
        return try {
            val obj = JsonParser.parseString(bodyStr).asJsonObject
            val errorElement = obj.get("error")

            when {
                errorElement == null || errorElement.isJsonNull -> {
                    Pair(obj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Error $statusCode", null)
                }
                errorElement.isJsonPrimitive -> Pair(errorElement.asString, null)
                errorElement.isJsonObject -> {
                    val errorObj = errorElement.asJsonObject
                    val code = errorObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                    val message = errorObj.get("message")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("message")?.takeIf { !it.isJsonNull }?.asString
                        ?: "Error $statusCode"
                    Pair(message, code)
                }
                else -> Pair("Error $statusCode", null)
            }
        } catch (_: Exception) {
            Pair("Error HTTP $statusCode", null)
        }
    }

    /**
     * Ejecuta la request y parsea la respuesta como ApiResponse<T>.
     * El backend siempre retorna { success: Boolean, data: T }
     */
    private suspend inline fun <reified T> execute(request: Request): ApiResult<T> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val backoff = INITIAL_BACKOFF_MS * attempt
                Log.w(TAG, "Retry attempt $attempt after ${backoff}ms for ${request.url}")
                delay(backoff)
            }
            try {
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    val (errorMsg, errorCode) = parseErrorResponse(bodyStr, response.code)
                    val isWhatsAppLinkPath = request.url.encodedPath.endsWith("/whatsapp/link")
                    val isWhatsAppBusinessRuleError =
                        errorCode == "WHATSAPP_OTP_SEND_FAILED" ||
                        errorCode == "WHATSAPP_RECIPIENT_NOT_ALLOWED_TEST_MODE" ||
                        errorCode == "WHATSAPP_ACCOUNT_NOT_REGISTERED" ||
                        errorMsg.contains("código de verificación por WhatsApp", ignoreCase = true) ||
                        errorMsg.contains("recipient phone number not in allowed list", ignoreCase = true) ||
                        errorMsg.contains("número no está permitido", ignoreCase = true) ||
                        errorMsg.contains("cuenta de WhatsApp activa", ignoreCase = true) ||
                        errorMsg.contains("account not registered", ignoreCase = true)

                    // On 401, attempt token refresh and retry once
                    if (response.code == 401 && attempt == 0 && !refreshToken.isNullOrBlank()) {
                        Log.i(TAG, "Got 401 on ${request.url} — attempting token refresh")
                        val refreshed = refreshAccessToken()
                        if (refreshed) {
                            // Rebuild request with new token and retry
                            val newRequest = request.newBuilder()
                                .headers(authHeaders().build())
                                .build()
                            try {
                                val retryResponse = client.newCall(newRequest).execute()
                                val retryBody = retryResponse.body?.string() ?: "{}"
                                if (retryResponse.isSuccessful) {
                                    val retryJson = JsonParser.parseString(retryBody).asJsonObject
                                    val retrySuccess = retryJson.get("success")?.asBoolean ?: false
                                    if (retrySuccess) {
                                        val dataElement = retryJson.get("data")
                                        if (dataElement == null || dataElement.isJsonNull) {
                                            @Suppress("UNCHECKED_CAST")
                                            return@withContext ApiResult.Success(null as T)
                                        }
                                        val data: T = gson.fromJson(dataElement, object : TypeToken<T>() {}.type)
                                        return@withContext ApiResult.Success(data)
                                    }
                                }
                                Log.w(TAG, "Retry after refresh also failed: ${retryResponse.code}")
                            } catch (retryEx: Exception) {
                                Log.w(TAG, "Retry after refresh threw: ${retryEx.message}")
                            }
                        }
                    }
                    // WhatsApp test-mode / business-rule errors should not be retried
                    if (isWhatsAppLinkPath && isWhatsAppBusinessRuleError) {
                        return@withContext ApiResult.Error(errorMsg, response.code)
                    }
                    // Don't retry other client errors (4xx)
                    if (response.code in 400..499 || errorCode == "WHATSAPP_UNAVAILABLE") {
                        return@withContext ApiResult.Error(errorMsg, response.code)
                    }
                    lastException = IOException(errorMsg)
                    continue
                }

                val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
                val success = jsonObj.get("success")?.asBoolean ?: false

                if (!success) {
                    val errorMsg = jsonObj.get("error")?.asString ?: "Unknown error"
                    return@withContext ApiResult.Error(errorMsg, response.code)
                }

                val dataElement = jsonObj.get("data")
                if (dataElement == null || dataElement.isJsonNull) {
                    @Suppress("UNCHECKED_CAST")
                    return@withContext ApiResult.Success(null as T)
                }

                val data: T = gson.fromJson(dataElement, object : TypeToken<T>() {}.type)
                return@withContext ApiResult.Success(data)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Timeout attempt $attempt for ${request.url}: ${e.message}")
                lastException = e
                continue
            } catch (e: UnknownHostException) {
                Log.w(TAG, "DNS resolution failed (attempt $attempt) for ${request.url}: ${e.message}")
                FallbackDnsResolver.clearCache()
                lastException = e
                continue
            } catch (e: IOException) {
                Log.w(TAG, "Network error attempt $attempt for ${request.url}: ${e.message}")
                lastException = e
                continue
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}", e)
                return@withContext ApiResult.Error("Error: ${e.message}", 0)
            }
        }
        Log.e(TAG, "All ${MAX_RETRIES + 1} attempts failed for ${request.url}", lastException)
        val userMessage = when (lastException) {
            is UnknownHostException -> "No se pudo conectar al servidor. Verifica tu conexión a internet."
            is SocketTimeoutException -> "El servidor tardó demasiado en responder. Intenta de nuevo."
            else -> "Error de red: ${lastException?.message ?: "timeout"}"
        }
        ApiResult.Error(userMessage, 0)
    }

    /** Versión que retorna List<T> */
    private suspend inline fun <reified T> executeList(request: Request): ApiResult<List<T>> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val backoff = INITIAL_BACKOFF_MS * attempt
                Log.w(TAG, "Retry list attempt $attempt after ${backoff}ms for ${request.url}")
                delay(backoff)
            }
            try {
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val obj = JsonParser.parseString(bodyStr).asJsonObject
                        obj.get("error")?.asString ?: "Error ${response.code}"
                    } catch (_: Exception) { "Error HTTP ${response.code}" }
                    // On 401, attempt token refresh and retry once
                    if (response.code == 401 && attempt == 0 && !refreshToken.isNullOrBlank()) {
                        Log.i(TAG, "Got 401 on ${request.url} (list) — attempting token refresh")
                        val refreshed = refreshAccessToken()
                        if (refreshed) {
                            val newRequest = request.newBuilder()
                                .headers(authHeaders().build())
                                .build()
                            try {
                                val retryResponse = client.newCall(newRequest).execute()
                                val retryBody = retryResponse.body?.string() ?: "{}"
                                if (retryResponse.isSuccessful) {
                                    val retryJson = JsonParser.parseString(retryBody).asJsonObject
                                    val dataElement = retryJson.get("data")
                                    if (dataElement == null || dataElement.isJsonNull) {
                                        return@withContext ApiResult.Success(emptyList())
                                    }
                                    val listType = TypeToken.getParameterized(List::class.java, T::class.java).type
                                    val data: List<T> = gson.fromJson(dataElement, listType)
                                    return@withContext ApiResult.Success(data)
                                }
                                Log.w(TAG, "Retry list after refresh failed: ${retryResponse.code}")
                            } catch (retryEx: Exception) {
                                Log.w(TAG, "Retry list after refresh threw: ${retryEx.message}")
                            }
                        }
                    }
                    if (response.code in 400..499) {
                        return@withContext ApiResult.Error(errorMsg, response.code)
                    }
                    lastException = IOException(errorMsg)
                    continue
                }

                val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
                val dataElement = jsonObj.get("data")

                if (dataElement == null || dataElement.isJsonNull) {
                    return@withContext ApiResult.Success(emptyList())
                }

                val listType = TypeToken.getParameterized(List::class.java, T::class.java).type
                val data: List<T> = gson.fromJson(dataElement, listType)
                return@withContext ApiResult.Success(data)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Timeout list attempt $attempt for ${request.url}: ${e.message}")
                lastException = e
                continue
            } catch (e: UnknownHostException) {
                Log.w(TAG, "DNS resolution failed (attempt $attempt) for ${request.url}: ${e.message}")
                FallbackDnsResolver.clearCache() // Forzar re-resolución en el próximo intento
                lastException = e
                continue
            } catch (e: IOException) {
                Log.w(TAG, "Network error list attempt $attempt for ${request.url}: ${e.message}")
                lastException = e
                continue
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                return@withContext ApiResult.Error("Error: ${e.message}", 0)
            }
        }
        Log.e(TAG, "All ${MAX_RETRIES + 1} list attempts failed for ${request.url}", lastException)
        val userMessage = when (lastException) {
            is UnknownHostException -> "No se pudo conectar al servidor. Verifica tu conexión a internet."
            is SocketTimeoutException -> "El servidor tardó demasiado en responder. Intenta de nuevo."
            else -> "Error de red: ${lastException?.message ?: "timeout"}"
        }
        ApiResult.Error(userMessage, 0)
    }

    data class PaginationMetadata(
        val page: Int,
        val pageSize: Int,
        val total: Int,
        val totalPages: Int
    )

    data class PaginatedResponse<T>(
        val data: List<T>,
        val pagination: PaginationMetadata?
    )

    /** Versión que retorna PaginatedResponse<T> */
    private suspend inline fun <reified T> executePaginated(request: Request): ApiResult<PaginatedResponse<T>> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) {
                val backoff = INITIAL_BACKOFF_MS * attempt
                Log.w(TAG, "Retry paginated attempt $attempt after ${backoff}ms for ${request.url}")
                delay(backoff)
            }
            try {
                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val obj = JsonParser.parseString(bodyStr).asJsonObject
                        obj.get("error")?.asString ?: "Error ${response.code}"
                    } catch (_: Exception) { "Error HTTP ${response.code}" }
                    // On 401, attempt token refresh and retry once
                    if (response.code == 401 && attempt == 0 && !refreshToken.isNullOrBlank()) {
                        Log.i(TAG, "Got 401 on ${request.url} (paginated) — attempting token refresh")
                        val refreshed = refreshAccessToken()
                        if (refreshed) {
                            val newRequest = request.newBuilder()
                                .headers(authHeaders().build())
                                .build()
                            try {
                                val retryResponse = client.newCall(newRequest).execute()
                                val retryBody = retryResponse.body?.string() ?: "{}"
                                if (retryResponse.isSuccessful) {
                                    val retryJson = JsonParser.parseString(retryBody).asJsonObject
                                    val dataEl = retryJson.get("data")
                                    val pagEl = retryJson.get("pagination")
                                    if (dataEl == null || dataEl.isJsonNull) {
                                        return@withContext ApiResult.Success(PaginatedResponse(emptyList(), null))
                                    }
                                    val listType = TypeToken.getParameterized(List::class.java, T::class.java).type
                                    val data: List<T> = gson.fromJson(dataEl, listType)
                                    var pagination: PaginationMetadata? = null
                                    if (pagEl != null && !pagEl.isJsonNull) {
                                        pagination = gson.fromJson(pagEl, PaginationMetadata::class.java)
                                    }
                                    return@withContext ApiResult.Success(PaginatedResponse(data, pagination))
                                }
                                Log.w(TAG, "Retry paginated after refresh failed: ${retryResponse.code}")
                            } catch (retryEx: Exception) {
                                Log.w(TAG, "Retry paginated after refresh threw: ${retryEx.message}")
                            }
                        }
                    }
                    if (response.code in 400..499) {
                        return@withContext ApiResult.Error(errorMsg, response.code)
                    }
                    lastException = IOException(errorMsg)
                    continue
                }

                val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
                val dataElement = jsonObj.get("data")
                val pagElement = jsonObj.get("pagination")

                if (dataElement == null || dataElement.isJsonNull) {
                    return@withContext ApiResult.Success(PaginatedResponse(emptyList(), null))
                }

                val listType = TypeToken.getParameterized(List::class.java, T::class.java).type
                val data: List<T> = gson.fromJson(dataElement, listType)

                var pagination: PaginationMetadata? = null
                if (pagElement != null && !pagElement.isJsonNull) {
                    pagination = gson.fromJson(pagElement, PaginationMetadata::class.java)
                }

                return@withContext ApiResult.Success(PaginatedResponse(data, pagination))
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Timeout paginated attempt $attempt for ${request.url}: ${e.message}")
                lastException = e
                continue
            } catch (e: UnknownHostException) {
                Log.w(TAG, "DNS resolution failed (attempt $attempt) for ${request.url}: ${e.message}")
                FallbackDnsResolver.clearCache()
                lastException = e
                continue
            } catch (e: IOException) {
                Log.w(TAG, "Network error paginated attempt $attempt for ${request.url}: ${e.message}")
                lastException = e
                continue
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                return@withContext ApiResult.Error("Error: ${e.message}", 0)
            }
        }
        Log.e(TAG, "All ${MAX_RETRIES + 1} paginated attempts failed for ${request.url}", lastException)
        val userMessage = when (lastException) {
            is UnknownHostException -> "No se pudo conectar al servidor. Verifica tu conexión a internet."
            is SocketTimeoutException -> "El servidor tardó demasiado en responder. Intenta de nuevo."
            else -> "Error de red: ${lastException?.message ?: "timeout"}"
        }
        ApiResult.Error(userMessage, 0)
    }

    suspend fun getCoursesPaginated(page: Int = 1, limit: Int = 50, excludeUserId: Long? = null): ApiResult<PaginatedResponse<Course>> {
        val excludeParam = if (excludeUserId != null && excludeUserId > 0) "&excludeUserId=$excludeUserId" else ""
        return executePaginated(get("/courses?page=$page&pageSize=$limit$excludeParam"))
    }

    suspend fun getFreeCoursesPaginated(page: Int = 1, limit: Int = 50, excludeUserId: Long? = null): ApiResult<PaginatedResponse<Course>> {
        val excludeParam = if (excludeUserId != null && excludeUserId > 0) "&excludeUserId=$excludeUserId" else ""
        return executePaginated(get("/courses/free?page=$page&pageSize=$limit$excludeParam"))
    }

    suspend fun getPremiumCoursesPaginated(page: Int = 1, limit: Int = 50, excludeUserId: Long? = null): ApiResult<PaginatedResponse<Course>> {
        val excludeParam = if (excludeUserId != null && excludeUserId > 0) "&excludeUserId=$excludeUserId" else ""
        return executePaginated(get("/courses/premium?page=$page&pageSize=$limit$excludeParam"))
    }

    suspend fun getCoursesByCreatorPaginated(username: String, page: Int = 1, limit: Int = 50): ApiResult<PaginatedResponse<Course>> =
        executePaginated(get("/courses/creator/$username?page=$page&pageSize=$limit"))

    suspend fun getCoursesByCreatorIdPaginated(userId: Long, page: Int = 1, limit: Int = 50): ApiResult<PaginatedResponse<Course>> =
        executePaginated(get("/courses/creator-id/$userId?page=$page&pageSize=$limit"))

    suspend fun getEnrolledCoursesPaginated(userId: Long? = null, page: Int = 1, limit: Int = 50): ApiResult<PaginatedResponse<Course>> {
        val uid = userId ?: currentUserId
        if (uid <= 0) return ApiResult.Error("User ID required", 400)
        return executePaginated(get("/courses/enrolled?userId=$uid&page=$page&pageSize=$limit"))
    }

    suspend fun getPurchasedCoursesPaginated(userId: Long? = null, page: Int = 1, limit: Int = 50): ApiResult<PaginatedResponse<Course>> {
        val uid = userId ?: currentUserId
        if (uid <= 0) return ApiResult.Error("User ID required", 400)
        return executePaginated(get("/courses/purchased?userId=$uid&page=$page&pageSize=$limit"))
    }

    suspend fun uploadAvatar(context: Context, fileUri: android.net.Uri): ApiResult<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            ensureTokenLoaded(context)
            if (jwtToken == null) return@withContext ApiResult.Error("Not logged in", 401)

            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(fileUri) ?: "image/jpeg"
            val contentLength = getUriContentLength(contentResolver, fileUri)
            
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "avatar", 
                    "avatar.${if (mimeType.contains("png")) "png" else "jpg"}", 
                    createProgressRequestBody(
                        resolver = contentResolver,
                        fileUri = fileUri,
                        mediaType = mimeType.toMediaType(),
                        contentLength = contentLength,
                        onProgress = { }
                    )
                )
                .build()

            val request = Request.Builder()
                .url("$apiBase/users/me/avatar")
                .headers(authHeaders().build())
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                 val errorMsg = try {
                    val obj = JsonParser.parseString(responseBody).asJsonObject
                    obj.get("error")?.asString ?: "Error ${response.code}"
                } catch (_: Exception) { "Error HTTP ${response.code}" }
                return@withContext ApiResult.Error(errorMsg, response.code)
            }
            
            val json = JsonParser.parseString(responseBody).asJsonObject
            if (json.has("data")) {
                val dataObj = json.get("data").asJsonObject
                if (dataObj.has("avatarUrl")) {
                     return@withContext ApiResult.Success(dataObj.get("avatarUrl").asString)
                }
            }
            // Fallback
            val profile = getMyProfile()
            if (profile is ApiResult.Success) {
                return@withContext ApiResult.Success(profile.data.avatar ?: "")
            }
            return@withContext ApiResult.Success("")
        } catch (e: Exception) {
            Log.e(TAG, "Upload avatar error", e)
            return@withContext ApiResult.Error("Exception: ${e.message}", 0)
        }
    }

    private fun ensureTokenLoaded(context: Context) {
        if (!::prefs.isInitialized) initialize(context)
    }

    // ═══════════════════════════════════════════════════════════
    // AUTH
    // ═══════════════════════════════════════════════════════════

    data class LoginRequest(val username: String, val password: String)
    data class RegisterRequest(
        val username: String,
        val password: String,
        val email: String,
        val personaId: Long? = null
    )
    data class RefreshTokenRequest(val refreshToken: String)
    data class RefreshTokenResponse(
        val accessToken: String?,
        val refreshToken: String?
    )
    data class AuthResponse(
        val accessToken: String?,
        val refreshToken: String? = null,
        val token: String? = null,
        val user: JsonObject?
    ) {
        /** Return whichever token field the backend provides */
        fun effectiveToken(): String? = accessToken ?: token
    }

    data class CreatorDashboardMetrics(
        val creatorUserId: Long = 0,
        val totalCourses: Int = 0,
        val enrolledUsersCount: Int = 0,
        val certifiedUsersCount: Int = 0,
        val completionRate: Int = 0,
        val approvalRate: Int = 0,
        val satisfactionRate: Int = 0,
    )

    data class EnrollmentChartSeries(
        val labels: List<String> = emptyList(),
        val values: List<Int> = emptyList(),
    )

    data class CreatorEnrollmentAnalytics(
        val weekly: EnrollmentChartSeries = EnrollmentChartSeries(),
        val monthly: EnrollmentChartSeries = EnrollmentChartSeries(),
        val totalEnrollments: Int = 0,
    )

    suspend fun login(username: String, password: String): ApiResult<AuthResponse> {
        val result = execute<AuthResponse>(post("/auth/login", LoginRequest(username, password)))
        if (result is ApiResult.Success && result.data?.effectiveToken() != null) {
            jwtToken = result.data.effectiveToken()
            result.data.refreshToken?.let { refreshToken = it }
            result.data.user?.get("id")?.asLong?.let { currentUserId = it }
        }
        return result
    }

    suspend fun register(
        username: String,
        password: String,
        email: String,
        personaId: Long? = null
    ): ApiResult<AuthResponse> {
        val result = execute<AuthResponse>(
            post("/auth/register", RegisterRequest(username, password, email, personaId))
        )
        if (result is ApiResult.Success && result.data?.effectiveToken() != null) {
            jwtToken = result.data.effectiveToken()
            result.data.refreshToken?.let { refreshToken = it }
            result.data.user?.get("id")?.asLong?.let { currentUserId = it }
        }
        return result
    }

    /**
     * Attempt to refresh the access token using the stored refresh token.
     * Returns true if a new access token was obtained, false otherwise.
     * Thread-safe: concurrent callers will wait for the first refresh to complete.
     */
    private suspend fun refreshAccessToken(): Boolean {
        val storedRefresh = refreshToken
        if (storedRefresh.isNullOrBlank()) {
            Log.w(TAG, "No refresh token available — cannot refresh")
            return false
        }

        return refreshMutex.withLock {
            // Double-check: if another coroutine already refreshed while we waited
            if (isRefreshing) return@withLock false
            isRefreshing = true
            try {
                Log.i(TAG, "Attempting token refresh...")
                val body = gson.toJson(RefreshTokenRequest(storedRefresh))
                val request = Request.Builder()
                    .url("$apiBase/auth/refresh-token")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()

                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                val bodyStr = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    Log.e(TAG, "Token refresh failed: HTTP ${response.code}")
                    // If refresh token is also expired/invalid, clear everything
                    if (response.code == 401 || response.code == 403) {
                        Log.w(TAG, "Refresh token rejected — clearing auth state")
                        jwtToken = null
                        refreshToken = null
                    }
                    return@withLock false
                }

                val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
                val success = jsonObj.get("success")?.asBoolean ?: false
                if (!success) {
                    Log.e(TAG, "Token refresh returned success=false")
                    return@withLock false
                }

                val dataObj = jsonObj.getAsJsonObject("data")
                val newAccess = dataObj?.get("accessToken")?.asString
                val newRefresh = dataObj?.get("refreshToken")?.asString

                if (newAccess.isNullOrBlank()) {
                    Log.e(TAG, "Token refresh returned no accessToken")
                    return@withLock false
                }

                jwtToken = newAccess
                if (!newRefresh.isNullOrBlank()) refreshToken = newRefresh
                Log.i(TAG, "Token refreshed successfully")
                return@withLock true
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh exception: ${e.message}", e)
                return@withLock false
            } finally {
                isRefreshing = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // USERS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMyProfile(): ApiResult<Usuario> = execute(get("/users/me"))

    suspend fun updateMyProfile(updates: Map<String, Any?>): ApiResult<Usuario> =
        execute(put("/users/me", updates))

    suspend fun searchUsers(query: String): ApiResult<List<Usuario>> =
        executeList(get("/users/search?q=$query"))

    suspend fun getUserById(id: Long): ApiResult<Usuario> {
        if (id <= 0) return ApiResult.Error("Invalid user ID: $id", 400)
        return execute(get("/users/$id"))
    }

    suspend fun getUserByUsername(username: String): ApiResult<Usuario> =
        execute(get("/users/by-username/$username"))

    suspend fun getUserByEmail(email: String): ApiResult<Usuario> =
        execute(get("/users/by-email/$email"))

    suspend fun getUsersByIds(ids: List<Long>): ApiResult<List<Usuario>> =
        executeList(get("/users/by-ids?ids=${ids.joinToString(",")}"))

    suspend fun registerFCMToken(token: String): ApiResult<JsonObject> =
        execute(post("/users/fcm-token", mapOf("fcmToken" to token)))

    suspend fun assignRole(userId: Long, roleId: Long): ApiResult<JsonObject> =
        execute(post("/users/$userId/role", mapOf("roleId" to roleId)))

    // ═══════════════════════════════════════════════════════════
    // PERSONAS
    // ═══════════════════════════════════════════════════════════

    suspend fun getPersonas(page: Int = 1, limit: Int = 50): ApiResult<List<Persona>> =
        executeList(get("/personas?page=$page&pageSize=$limit"))

    suspend fun getPersonaById(id: Long): ApiResult<Persona> =
        execute(get("/personas/$id"))

    suspend fun getPersonaByIdentificacion(identificacion: String): ApiResult<Persona> =
        execute(get("/personas/by-identificacion/$identificacion"))

    suspend fun createPersona(persona: Persona): ApiResult<Persona> =
        execute(post("/personas", persona))

    suspend fun updatePersona(id: Long, updates: Map<String, Any?>): ApiResult<Persona> =
        execute(put("/personas/$id", updates))

    suspend fun deletePersona(id: Long): ApiResult<JsonObject> =
        execute(delete("/personas/$id"))

    // ═══════════════════════════════════════════════════════════
    // ROLES
    // ═══════════════════════════════════════════════════════════

    suspend fun getRoles(): ApiResult<List<Rol>> =
        executeList(get("/roles"))

    suspend fun getRolById(id: Long): ApiResult<Rol> =
        execute(get("/roles/$id"))

    suspend fun getUserRoles(userId: Long): ApiResult<List<Long>> =
        executeList(get("/roles/user/$userId"))

    suspend fun isDocente(userId: Long): ApiResult<Boolean> =
        execute(get("/roles/user/$userId/is-docente"))

    suspend fun promoteToDocente(userId: Long): ApiResult<JsonObject> =
        execute(post("/roles/user/$userId/promote-docente", null))

    suspend fun ensureCreatorRole(userId: Long): ApiResult<JsonObject> =
        execute(post("/roles/user/$userId/ensure-creator", null))

    suspend fun syncAllCreatorRoles(): ApiResult<JsonObject> =
        execute(post("/roles/sync-creator-roles", null))

    // ═══════════════════════════════════════════════════════════
    // RECURSOS
    // ═══════════════════════════════════════════════════════════

    suspend fun getRecursos(): ApiResult<List<Recurso>> =
        executeList(get("/roles/recursos/all"))

    suspend fun getRecursosByRol(rolId: Long): ApiResult<List<Recurso>> =
        executeList(get("/roles/recursos/by-rol/$rolId"))

    suspend fun getRolRecursos(): ApiResult<List<RolRecurso>> =
        executeList(get("/roles/rol-recursos/all"))

    suspend fun getRolRecursosByRol(rolId: Long): ApiResult<List<RolRecurso>> =
        executeList(get("/roles/rol-recursos/by-rol/$rolId"))

    // ═══════════════════════════════════════════════════════════
    // COURSES
    // ═══════════════════════════════════════════════════════════

    suspend fun getCourses(page: Int = 1, limit: Int = 50): ApiResult<List<Course>> =
        executeList(get("/courses?page=$page&pageSize=$limit"))

    /**
     * Get courses with full metadata (total, pagination) as raw JsonObject.
     * Useful for getting total count without fetching all items.
     */
    suspend fun getCoursesMetadata(page: Int = 1, limit: Int = 1): ApiResult<JsonObject> =
        execute(get("/courses/stats"))

    /**
     * Get filter counts for all course categories in a single server call.
     * Returns a JsonObject with keys: total, free, premium, myCreated, otherCreators, enrolled, purchased
     * @param userId optional user ID for user-specific counts (defaults to current user)
     */
    suspend fun getCourseFilterCounts(userId: Long? = null): ApiResult<JsonObject> {
        val uid = userId ?: currentUserId
        val param = if (uid > 0) "?userId=$uid" else ""
        return execute(get("/courses/counts$param"))
    }

    suspend fun searchCourses(query: String): ApiResult<List<Course>> =
        executeList(get("/courses/search?q=${Uri.encode(query)}"))

    suspend fun getFreeCourses(): ApiResult<List<Course>> =
        executeList(get("/courses/free"))

    suspend fun getPremiumCourses(page: Int = 1, limit: Int = 50): ApiResult<List<Course>> =
        executeList(get("/courses/premium?page=$page&pageSize=$limit"))

    suspend fun getPurchasedCourses(userId: Long? = null, page: Int = 1, limit: Int = 50): ApiResult<List<Course>> {
       val uid = userId ?: currentUserId
       if (uid <= 0) return ApiResult.Error("User ID required", 400)
       return executeList(get("/courses/purchased?userId=$uid&page=$page&pageSize=$limit"))
    }

    suspend fun getCoursesByIds(ids: List<Long>): ApiResult<List<Course>> =
        executeList(get("/courses/by-ids?ids=${ids.joinToString(",")}"))

    suspend fun getCoursesByCreator(username: String): ApiResult<List<Course>> =
        executeList(get("/courses/creator/$username"))

    suspend fun getCoursesByCreatorId(userId: Long): ApiResult<List<Course>> =
        executeList(get("/courses/creator-id/$userId"))

    suspend fun getMyCreatorDashboardMetrics(): ApiResult<CreatorDashboardMetrics> =
        execute(get("/courses/my/creator-metrics"))

    suspend fun getCourseById(id: Long): ApiResult<Course> =
        execute(get("/courses/$id"))

    suspend fun createCourse(courseData: Map<String, Any?>): ApiResult<Course> =
        execute(post("/courses", courseData))

    suspend fun createCourse(course: Course): ApiResult<Course> {
        var username = "unknown"
        if (course.creatorUserId > 0) {
             val userResult = getUserById(course.creatorUserId)
             if (userResult is ApiResult.Success) {
                 username = userResult.data.usuario
             }
        }
        val payload = mapOf(
            "title" to course.title,
            "description" to course.description,
            "category" to course.category,
            "thumbnailUri" to course.thumbnailUri,
            "creatorUsername" to username,
            "isFree" to !course.isPremium,
            "price" to course.price,
            "isPublished" to course.isPublished
        )
        return execute(post("/courses", payload))
    }

    suspend fun updateCourse(id: Long, updates: Map<String, Any?>): ApiResult<Course> =
        execute(put("/courses/$id", updates))

    suspend fun deleteCourse(id: Long): ApiResult<JsonObject> =
        execute(delete("/courses/$id"))

    // ═══════════════════════════════════════════════════════════
    // VIDEOS
    // ═══════════════════════════════════════════════════════════

    suspend fun getVideos(page: Int = 1, limit: Int = 50): ApiResult<List<VideoData>> {
        // Intenta primero la ruta autenticada; si falla con 401, usa la ruta pública
        val authResult = executeList<VideoData>(get("/videos?page=$page&pageSize=$limit"))
        if (authResult is ApiResult.Error && authResult.code == 401) {
            Log.d(TAG, "Auth required for /videos, falling back to /public/videos")
            return executeList(publicGet("/public/videos?page=$page&pageSize=$limit"))
        }
        return authResult
    }

    /**
     * Feed público de videos — no requiere autenticación.
     * Útil para browsing inicial antes de login.
     */
    suspend fun getPublicVideos(page: Int = 1, limit: Int = 50): ApiResult<List<VideoData>> =
        executeList(publicGet("/public/videos?page=$page&pageSize=$limit"))

    suspend fun searchVideos(query: String): ApiResult<List<VideoData>> =
        executeList(get("/videos/search?q=$query"))

    suspend fun getVideosByCourseIds(courseIds: List<Long>): ApiResult<List<VideoData>> =
        executeList(get("/videos/by-courses?ids=${courseIds.joinToString(",")}"))

    suspend fun getVideosByCreator(username: String): ApiResult<List<VideoData>> =
        executeList(get("/videos/creator/$username"))

    suspend fun getVideosByCourse(courseId: Long): ApiResult<List<VideoData>> =
        executeList(get("/videos/course/$courseId"))

    suspend fun getVideoById(id: Long): ApiResult<VideoData> =
        execute(get("/videos/$id"))

    suspend fun createVideo(video: VideoData): ApiResult<VideoData> =
        execute(post("/videos", video))

    suspend fun updateVideo(id: Long, updates: Map<String, Any?>): ApiResult<VideoData> =
        execute(put("/videos/$id", updates))

    suspend fun deleteVideo(id: Long): ApiResult<JsonObject> =
        execute(delete("/videos/$id"))

    /**
     * Batch-fetch pre-signed URLs for multiple video IDs in a single request.
     * Used by [VideoPreloader] to resolve adjacent video URLs in one round-trip
     * instead of N individual calls.
     *
     * @param videoIds List of video IDs to sign.
     * @return JsonObject keyed by video ID, each value containing { videoUrl, thumbnailUrl }.
     */
    suspend fun batchSignedUrls(videoIds: List<Long>): ApiResult<JsonObject> =
        execute(post("/videos/batch-signed-urls", mapOf("videoIds" to videoIds)))

    // ═══════════════════════════════════════════════════════════
    // STREAMING — Optimized video feed for instant playback
    // ═══════════════════════════════════════════════════════════

    /**
     * Optimized video feed — returns videos with ALL URLs pre-signed
     * and ready for immediate playback. Backed by server-side URL cache.
     *
     * Uses the /streaming/feed endpoint which:
     *  1. Signs ALL URLs (including relative R2 keys) in parallel
     *  2. Caches signed URLs in memory (50-min TTL)
     *  3. Returns Cache-Control headers for client-side caching
     *
     * Falls back to public endpoint if auth fails.
     */
    suspend fun getStreamingFeed(page: Int = 1, limit: Int = 20): ApiResult<List<VideoData>> {
        val authResult = executeList<VideoData>(get("/streaming/feed?page=$page&pageSize=$limit"))
        if (authResult is ApiResult.Error && authResult.code == 401) {
            return executeList(publicGet("/public/streaming/feed?page=$page&pageSize=$limit"))
        }
        return authResult
    }

    /**
     * Public streaming feed — no auth required, for browsing before login.
     */
    suspend fun getPublicStreamingFeed(page: Int = 1, limit: Int = 20): ApiResult<List<VideoData>> =
        executeList(publicGet("/public/streaming/feed?page=$page&pageSize=$limit"))

    /**
     * Batch-sign URLs via the streaming service (with server-side caching).
     * Faster than the regular batchSignedUrls because of in-memory cache.
     */
    suspend fun streamingBatchSign(videoIds: List<Long>): ApiResult<JsonObject> =
        execute(post("/streaming/batch-sign", mapOf("videoIds" to videoIds)))

    /**
     * Upload a video file (and optional thumbnail) via multipart to the backend.
     * The backend handles all R2 storage operations — the client never talks to R2 directly.
     *
     * @param context Android context for content resolver
     * @param videoUri URI of the video file to upload
     * @param thumbnailUri URI of the thumbnail image (optional)
     * @param title Video title
     * @param description Video description
     * @param isPaid Whether the video is paid content
     * @param price Price (if paid)
     * @param courseId Associated course ID (optional)
     * @param onProgress Progress callback (0-100)
     */
    suspend fun uploadVideoWithFiles(
        context: Context,
        videoUri: android.net.Uri,
        thumbnailUri: android.net.Uri? = null,
        title: String,
        description: String = "",
        isPaid: Boolean = false,
        price: Double? = null,
        courseId: Long? = null,
        onProgress: ((Int) -> Unit)? = null
    ): ApiResult<VideoData> = withContext(Dispatchers.IO) {
        try {
            ensureTokenLoaded(context)
            if (jwtToken == null) return@withContext ApiResult.Error("Not logged in", 401)

            onProgress?.invoke(5)

            val resolver = context.contentResolver

            val videoMime = resolver.getType(videoUri) ?: "video/mp4"
            val videoLength = getUriContentLength(resolver, videoUri)

            onProgress?.invoke(15)

            // Build multipart body
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "video",
                    "${title.replace(Regex("[^a-zA-Z0-9]"), "_")}.mp4",
                    createProgressRequestBody(
                        resolver = resolver,
                        fileUri = videoUri,
                        mediaType = videoMime.toMediaType(),
                        contentLength = videoLength
                    ) { progress ->
                        // Map upload progress from 20% to 85%
                        val mapped = 20 + (progress * 0.65).toInt()
                        onProgress?.invoke(mapped)
                    }
                )
                .addFormDataPart("title", title)
                .addFormDataPart("description", description)
                .addFormDataPart("isPaid", isPaid.toString())
                .apply {
                    price?.let { addFormDataPart("price", it.toString()) }
                    courseId?.let { addFormDataPart("courseId", it.toString()) }
                    addFormDataPart("timestamp", System.currentTimeMillis().toString())
                }

            // Attach thumbnail if provided
            if (thumbnailUri != null) {
                val thumbMime = resolver.getType(thumbnailUri) ?: "image/jpeg"
                val thumbLength = getUriContentLength(resolver, thumbnailUri)
                multipartBuilder.addFormDataPart(
                    "thumbnail",
                    "thumbnail.jpg",
                    createProgressRequestBody(
                        resolver = resolver,
                        fileUri = thumbnailUri,
                        mediaType = thumbMime.toMediaType(),
                        contentLength = thumbLength
                    ) { _ -> }
                )
            }

            val requestBody = multipartBuilder.build()

            // Use a longer-timeout client for large video uploads
            val uploadClient = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(600, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url("$apiBase/videos/upload")
                .headers(Headers.Builder().apply {
                    jwtToken?.let { add("Authorization", "Bearer $it") }
                }.build())
                .post(requestBody)
                .build()

            onProgress?.invoke(20)

            val response = uploadClient.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            onProgress?.invoke(90)

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val obj = JsonParser.parseString(bodyStr).asJsonObject
                    obj.get("error")?.asString ?: "Error ${response.code}"
                } catch (_: Exception) { "Error HTTP ${response.code}" }
                return@withContext ApiResult.Error(errorMsg, response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val success = jsonObj.get("success")?.asBoolean ?: false
            if (!success) {
                val errorMsg = jsonObj.get("error")?.asString ?: "Unknown error"
                return@withContext ApiResult.Error(errorMsg, response.code)
            }

            val dataElement = jsonObj.get("data")
            if (dataElement == null || dataElement.isJsonNull) {
                return@withContext ApiResult.Error("No data returned", 0)
            }

            val video: VideoData = gson.fromJson(dataElement, VideoData::class.java)
            onProgress?.invoke(100)
            return@withContext ApiResult.Success(video)
        } catch (e: Exception) {
            Log.e(TAG, "uploadVideoWithFiles error", e)
            return@withContext ApiResult.Error("Upload error: ${e.message}", 0)
        }
    }

    /**
     * Creates a RequestBody that reports write progress.
     */
    private fun createProgressRequestBody(
        resolver: android.content.ContentResolver,
        fileUri: android.net.Uri,
        mediaType: okhttp3.MediaType,
        contentLength: Long,
        onProgress: (Int) -> Unit
    ): okhttp3.RequestBody {
        return object : okhttp3.RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = contentLength

            override fun writeTo(sink: okio.BufferedSink) {
                val inputStream = resolver.openInputStream(fileUri)
                    ?: throw java.io.IOException("Cannot open file stream for upload: $fileUri")

                inputStream.use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    var read = stream.read(buffer)
                    while (read != -1) {
                        sink.write(buffer, 0, read)
                        written += read
                        if (contentLength > 0L) {
                            val progress = ((written * 100) / contentLength).toInt().coerceIn(0, 100)
                            onProgress(progress)
                        }
                        read = stream.read(buffer)
                    }
                }
            }
        }
    }

    private fun getUriContentLength(resolver: android.content.ContentResolver, fileUri: android.net.Uri): Long {
        return try {
            resolver.openAssetFileDescriptor(fileUri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TOPICS
    // ═══════════════════════════════════════════════════════════

    suspend fun getTopics(page: Int = 1, limit: Int = 50): ApiResult<List<Topic>> =
        executeList(get("/topics?page=$page&pageSize=$limit"))

    suspend fun getTopicsByCourse(courseId: Long): ApiResult<List<Topic>> =
        executeList(get("/topics/course/$courseId"))

    suspend fun getTopicById(id: Long): ApiResult<Topic> =
        execute(get("/topics/$id"))

    suspend fun createTopic(topic: Topic): ApiResult<Topic> =
        execute(post("/topics", topic))

    suspend fun updateTopic(id: Long, updates: Map<String, Any?>): ApiResult<Topic> =
        execute(put("/topics/$id", updates))

    suspend fun deleteTopic(id: Long): ApiResult<JsonObject> =
        execute(delete("/topics/$id"))

    // ═══════════════════════════════════════════════════════════
    // TASKS
    // ═══════════════════════════════════════════════════════════

    suspend fun getTasks(page: Int = 1, limit: Int = 50): ApiResult<List<Task>> =
        executeList(get("/tasks?page=$page&pageSize=$limit"))

    suspend fun getTasksByTopic(topicId: Long): ApiResult<List<Task>> =
        executeList(get("/tasks/topic/$topicId"))

    suspend fun getTasksByCourse(courseId: Long): ApiResult<List<Task>> =
        executeList(get("/tasks/course/$courseId"))

    suspend fun getTaskById(id: Long): ApiResult<Task> =
        execute(get("/tasks/$id"))

    suspend fun createTask(task: Task): ApiResult<Task> =
        execute(post("/tasks", task))

    suspend fun updateTask(id: Long, updates: Map<String, Any?>): ApiResult<Task> =
        execute(put("/tasks/$id", updates))

    suspend fun deleteTask(id: Long): ApiResult<JsonObject> =
        execute(delete("/tasks/$id"))

    // ═══════════════════════════════════════════════════════════
    // CONTENT ITEMS
    // ═══════════════════════════════════════════════════════════

    suspend fun getContentItemsByTask(taskId: Long): ApiResult<List<ContentItem>> =
        executeList(get("/content-items/task/$taskId"))

    suspend fun getContentItemsByTopic(topicId: Long): ApiResult<List<ContentItem>> =
        executeList(get("/content-items/topic/$topicId"))

    suspend fun getContentItemById(id: Long): ApiResult<ContentItem> =
        execute(get("/content-items/$id"))

    suspend fun createContentItem(item: ContentItem): ApiResult<ContentItem> =
        execute(post("/content-items", item))

    suspend fun updateContentItem(id: Long, updates: Map<String, Any?>): ApiResult<ContentItem> =
        execute(put("/content-items/$id", updates))

    suspend fun deleteContentItem(id: Long): ApiResult<JsonObject> =
        execute(delete("/content-items/$id"))

    suspend fun deleteContentItemsByTask(taskId: Long): ApiResult<JsonObject> =
        execute(delete("/content-items/by-task/$taskId"))

    // ═══════════════════════════════════════════════════════════
    // SUBMISSIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMySubmissions(page: Int = 1, limit: Int = 20): ApiResult<List<TaskSubmission>> =
        executeList(get("/submissions/my?page=$page&pageSize=$limit"))

    suspend fun getSubmissionsByTask(taskId: Long, page: Int = 1, limit: Int = 50): ApiResult<List<TaskSubmission>> =
        executeList(get("/submissions/task/$taskId?page=$page&pageSize=$limit"))

    suspend fun getSubmissionByUserAndTask(taskId: Long, studentId: Long? = null): ApiResult<TaskSubmission?> {
        val query = if (studentId != null) "?studentId=$studentId" else ""
        return execute(get("/submissions/task/$taskId/student$query"))
    }

    suspend fun getSubmissionsByCourse(courseId: Long, page: Int = 1, limit: Int = 50, ungradedOnly: Boolean = false): ApiResult<List<TaskSubmission>> {
        val ungradedParam = if (ungradedOnly) "&ungradedOnly=true" else ""
        return executeList(get("/submissions/course/$courseId?page=$page&pageSize=$limit$ungradedParam"))
    }

    suspend fun getSubmissionById(id: Long): ApiResult<TaskSubmission> =
        execute(get("/submissions/$id"))

    suspend fun submitWork(submission: Map<String, Any?>): ApiResult<TaskSubmission> =
        execute(post("/submissions", submission))

    suspend fun gradeSubmission(id: Long, grade: Float, feedback: String?): ApiResult<TaskSubmission> =
        execute(put("/submissions/$id/grade", mapOf("grade" to grade, "feedback" to feedback)))

    suspend fun deleteSubmission(id: Long): ApiResult<JsonObject> =
        execute(delete("/submissions/$id"))

    // ═══════════════════════════════════════════════════════════
    // SUBSCRIPTIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMySubscriptions(): ApiResult<List<Subscription>> =
        executeList(get("/subscriptions/my"))

    suspend fun getMySubscriptionCount(): ApiResult<Int> =
        execute(get("/subscriptions/my/count"))

    suspend fun getMySubscribedCreators(): ApiResult<List<Long>> =
        executeList(get("/subscriptions/my/creators"))

    suspend fun getMySubscribers(): ApiResult<List<Subscription>> =
        executeList(get("/subscriptions/subscribers"))

    suspend fun checkSubscription(creatorId: Long): ApiResult<Boolean> {
        return try {
            val request = get("/subscriptions/check/$creatorId")
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                return ApiResult.Error("Error ${response.code}", response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val dataElement = jsonObj.get("data")

            if (dataElement == null || dataElement.isJsonNull) {
                return ApiResult.Success(false)
            }

            val isSubscribed = when {
                dataElement.isJsonPrimitive -> dataElement.asBoolean
                dataElement.isJsonObject -> dataElement.asJsonObject.get("isSubscribed")?.asBoolean ?: false
                else -> false
            }
            ApiResult.Success(isSubscribed)
        } catch (e: Exception) {
            Log.e(TAG, "Error checkSubscription: ${e.message}", e)
            ApiResult.Error("Error: ${e.message}", 0)
        }
    }

    suspend fun getSubscriberCount(creatorId: Long): ApiResult<Int> {
        return try {
            val request = get("/subscriptions/count/$creatorId")
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                return ApiResult.Error("Error ${response.code}", response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val dataElement = jsonObj.get("data")

            if (dataElement == null || dataElement.isJsonNull) {
                return ApiResult.Success(0)
            }

            val count = when {
                dataElement.isJsonPrimitive -> dataElement.asInt
                dataElement.isJsonObject -> dataElement.asJsonObject.get("count")?.asInt ?: 0
                else -> 0
            }
            ApiResult.Success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Error getSubscriberCount: ${e.message}", e)
            ApiResult.Error("Error: ${e.message}", 0)
        }
    }

    suspend fun subscribe(creatorId: Long): ApiResult<Subscription> =
        execute(post("/subscriptions/$creatorId", null))

    suspend fun unsubscribe(creatorId: Long): ApiResult<JsonObject> =
        execute(delete("/subscriptions/$creatorId"))

    // ═══════════════════════════════════════════════════════════
    // PROGRESS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMyProgress(): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/my"))

    suspend fun getMyProgressForMyCourses(): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/my?creatorOnly=true"))

    suspend fun getMyEnrolledCourseIds(): ApiResult<List<Long>> =
        executeList(get("/progress/my/enrolled-course-ids"))

    suspend fun getTopStudentsByCreatorCourses(limit: Int = 10): ApiResult<List<JsonObject>> =
        executeList(get("/progress/top-students?limit=$limit"))

    suspend fun getTopStudents(limit: Int = 10): ApiResult<List<JsonObject>> =
        getTopStudentsByCreatorCourses(limit)

    suspend fun getCreatorEnrollmentAnalytics(
        weeklyDays: Int = 7,
        monthlyMonths: Int = 6,
    ): ApiResult<CreatorEnrollmentAnalytics> =
        execute(get("/progress/creator/enrollment-analytics?weeklyDays=$weeklyDays&monthlyMonths=$monthlyMonths"))

    suspend fun getProgressByCourse(courseId: Long): ApiResult<ProgresoEstudiante> =
        execute(get("/progress/course/$courseId"))

    suspend fun getProgressByCourseRaw(courseId: Long): ApiResult<JsonObject> =
        execute(get("/progress/course/$courseId"))

    suspend fun getAllProgressByCourse(courseId: Long): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/course/$courseId/all"))

    suspend fun getEnrolledCount(courseId: Long): ApiResult<Int> {
        return try {
            val result = execute<JsonObject>(get("/progress/course/$courseId/enrolled-count"))
            if (result is ApiResult.Success) {
                val count = result.data?.get("count")?.asInt ?: 0
                ApiResult.Success(count)
            } else {
                ApiResult.Error("Error fetching count", 0)
            }
        } catch (e: Exception) {
            ApiResult.Error("Error parsing enrolled count", 0)
        }
    }

    suspend fun isEnrolled(courseId: Long): ApiResult<Boolean> {
        return try {
            val result = execute<JsonObject>(get("/progress/course/$courseId/enrolled"))
            if (result is ApiResult.Success) {
                // Return "isEnrolled" field, or false if missing
                val enrolled = result.data?.get("isEnrolled")?.asBoolean ?: false
                ApiResult.Success(enrolled)
            } else {
                ApiResult.Error("Error checking enrollment", 0)
            }
        } catch (e: Exception) {
            ApiResult.Error("Error checking enrollment", 0)
        }
    }

    suspend fun getLeaderboard(courseId: Long, limit: Int = 10): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/leaderboard/$courseId?limit=$limit"))

    suspend fun upsertProgress(data: Map<String, Any?>): ApiResult<ProgresoEstudiante> =
        execute(post("/progress/upsert", data))

    suspend fun issueCertificate(courseId: Long, userId: Long? = null): ApiResult<ProgresoEstudiante> {
        val body = if (userId != null) mapOf("userId" to userId) else emptyMap<String, Any>()
        return execute(post("/progress/course/$courseId/certificate", body))
    }

    suspend fun updateCertificateUrl(courseId: Long, certificateUrl: String, userId: Long? = null): ApiResult<ProgresoEstudiante> {
        val body = mutableMapOf<String, Any?>("certificateUrl" to certificateUrl)
        if (userId != null) body["userId"] = userId
        return execute(put("/progress/course/$courseId/certificate-url", body))
    }

    suspend fun recalculateProgress(courseId: Long): ApiResult<JsonObject> =
        execute(post("/progress/course/$courseId/recalculate", null))

    // ═══════════════════════════════════════════════════════════
    // LIKES
    // ═══════════════════════════════════════════════════════════

    suspend fun getLikesByEntity(entityType: String, entityId: Long): ApiResult<JsonObject> =
        execute(get("/likes/count/$entityType/$entityId"))

    suspend fun toggleLike(entityType: String, entityId: Long): ApiResult<JsonObject> =
        execute(post("/likes/toggle", mapOf("entityType" to entityType, "entityId" to entityId)))

    suspend fun checkLike(entityType: String, entityId: Long): ApiResult<Boolean> =
        execute(get("/likes/check/$entityType/$entityId"))

    // ═══════════════════════════════════════════════════════════
    // COMMENTS
    // ═══════════════════════════════════════════════════════════

    suspend fun getCommentsByVideo(videoId: Long, page: Int = 1, limit: Int = 50): ApiResult<List<VideoComment>> =
        executeList(get("/comments/video/$videoId?page=$page&pageSize=$limit"))

    suspend fun getCommentCountByVideo(videoId: Long): ApiResult<JsonObject> =
        execute(get("/comments/video/$videoId/count"))

    suspend fun createComment(videoId: Long, comment: String, parentId: Long? = null): ApiResult<VideoComment> {
        val body = mutableMapOf<String, Any?>("videoId" to videoId, "content" to comment)
        if (parentId != null) body["parentId"] = parentId
        return execute(post("/comments", body))
    }

    suspend fun updateComment(id: Long, comment: String): ApiResult<VideoComment> =
        execute(put("/comments/$id", mapOf("comment" to comment)))

    suspend fun deleteComment(id: Long): ApiResult<JsonObject> =
        execute(delete("/comments/$id"))

    // ═══════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMyNotifications(page: Int = 1, limit: Int = 50): ApiResult<List<Notification>> {
        return try {
            val request = get("/notifications/my?page=$page&pageSize=$limit")
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val obj = JsonParser.parseString(bodyStr).asJsonObject
                    obj.get("error")?.asString ?: "Error ${response.code}"
                } catch (_: Exception) { "Error HTTP ${response.code}" }
                return ApiResult.Error(errorMsg, response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val dataElement = jsonObj.get("data")

            if (dataElement == null || dataElement.isJsonNull) {
                return ApiResult.Success(emptyList())
            }

            // Backend returns { notifications: [...], unreadCount: N } inside data
            if (dataElement.isJsonObject) {
                val dataObj = dataElement.asJsonObject
                val notificationsElement = dataObj.get("notifications")
                if (notificationsElement != null && notificationsElement.isJsonArray) {
                    val listType = TypeToken.getParameterized(List::class.java, Notification::class.java).type
                    val list: List<Notification> = gson.fromJson(notificationsElement, listType)
                    return ApiResult.Success(list)
                }
            }

            // Fallback: data is directly an array
            if (dataElement.isJsonArray) {
                val listType = TypeToken.getParameterized(List::class.java, Notification::class.java).type
                val list: List<Notification> = gson.fromJson(dataElement, listType)
                return ApiResult.Success(list)
            }

            ApiResult.Success(emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Error getMyNotifications: ${e.message}", e)
            ApiResult.Error("Error: ${e.message}", 0)
        }
    }

    suspend fun getUnreadNotificationCount(): ApiResult<Int> {
        return try {
            val request = get("/notifications/unread-count")
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                return ApiResult.Error("Error ${response.code}", response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val dataElement = jsonObj.get("data")

            if (dataElement == null || dataElement.isJsonNull) {
                return ApiResult.Success(0)
            }

            // data can be a plain number or an object with a count field
            val count = when {
                dataElement.isJsonPrimitive -> dataElement.asInt
                dataElement.isJsonObject -> dataElement.asJsonObject.get("count")?.asInt ?: 0
                else -> 0
            }
            ApiResult.Success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Error getUnreadNotificationCount: ${e.message}", e)
            ApiResult.Error("Error: ${e.message}", 0)
        }
    }

    suspend fun markNotificationAsRead(id: Long): ApiResult<JsonObject> =
        execute(put("/notifications/$id/read", null))

    suspend fun markAllNotificationsAsRead(): ApiResult<JsonObject> =
        execute(put("/notifications/read-all", null))

    suspend fun sendNotification(
        userId: Long,
        title: String,
        message: String,
        type: String? = null,
        relatedId: Long? = null,
        senderUsername: String? = null,
        thumbnailUrl: String? = null,
        metadata: String? = null,
        senderAvatarUrl: String? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "userId" to userId,
            "title" to title,
            "message" to message
        )
        type?.let { body["type"] = it }
        relatedId?.let { body["relatedId"] = it }
        senderUsername?.let { body["senderUsername"] = it }
        senderAvatarUrl?.let { body["senderAvatarUrl"] = it }
        thumbnailUrl?.let { body["thumbnailUrl"] = it }
        metadata?.let { body["metadata"] = it }
        return execute(post("/notifications/send", body))
    }

    suspend fun sendNotificationToMultiple(
        userIds: List<Long>,
        title: String,
        message: String,
        type: String? = null,
        data: Map<String, Any?>? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "userIds" to userIds,
            "title" to title,
            "message" to message
        )
        type?.let { body["type"] = it }
        data?.let { body["data"] = it }
        return execute(post("/notifications/send-multiple", body))
    }

    suspend fun deleteNotification(id: Long): ApiResult<JsonObject> =
        execute(delete("/notifications/$id"))

    // ═══════════════════════════════════════════════════════════
    // PAYMENTS
    // ═══════════════════════════════════════════════════════════

    suspend fun initiatePayment(request: Map<String, Any?>): ApiResult<JsonObject> =
        execute(post("/payment/initiate", request))

    suspend fun getPaymentStatus(transactionId: String): ApiResult<JsonObject> =
        execute(get("/payment/status/$transactionId"))

    suspend fun getMyTransactions(): ApiResult<List<JsonObject>> =
        executeList(get("/payment/my"))

    suspend fun getBanks(): ApiResult<List<JsonObject>> =
        executeList(get("/payment/banks"))

    suspend fun hasPurchasedCourse(courseId: Long): ApiResult<Boolean> =
        execute(get("/payment/purchased/$courseId"))

    // ═══════════════════════════════════════════════════════════
    // STORAGE (R2)
    // ═══════════════════════════════════════════════════════════

    suspend fun getUploadUrl(
        fileName: String,
        contentType: String,
        folder: String? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "fileName" to fileName,
            "contentType" to contentType
        )
        folder?.let { body["folder"] = it }
        return execute(post("/storage/upload-url", body))
    }

    suspend fun getDownloadUrl(key: String): ApiResult<JsonObject> =
        execute(get("/storage/download?key=$key"))

    suspend fun listStorageFiles(prefix: String? = null): ApiResult<List<JsonObject>> {
        val query = if (prefix != null) "?prefix=$prefix" else ""
        return executeList(get("/storage/list$query"))
    }

    suspend fun deleteStorageFile(key: String): ApiResult<JsonObject> =
        execute(delete("/storage?key=$key"))

    /**
     * Sube un archivo directamente al backend (multipart).
     * Útil para archivos pequeños; para grandes usar getUploadUrl + upload directo a R2.
     */
    suspend fun uploadFile(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        folder: String? = null
    ): ApiResult<JsonObject> {
        return try {
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    fileBytes.toRequestBody(mimeType.toMediaType())
                )
                .apply { folder?.let { addFormDataPart("folder", it) } }
                .build()

            val request = Request.Builder()
                .url("$apiBase/storage/upload")
                .headers(Headers.Builder().apply {
                    jwtToken?.let { add("Authorization", "Bearer $it") }
                }.build())
                .post(multipartBody)
                .build()

            execute(request)
        } catch (e: Exception) {
            ApiResult.Error("Upload error: ${e.message}", 0)
        }
    }

    /**
     * Sube un archivo al backend por streaming desde un Uri, sin cargar todo en memoria.
     */
    suspend fun uploadFile(
        context: Context,
        fileUri: android.net.Uri,
        fileName: String,
        mimeType: String,
        folder: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): ApiResult<JsonObject> = withContext(Dispatchers.IO) {
        try {
            ensureTokenLoaded(context)
            if (jwtToken == null) return@withContext ApiResult.Error("Not logged in", 401)

            val resolver = context.contentResolver
            val contentLength = getUriContentLength(resolver, fileUri)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    createProgressRequestBody(
                        resolver = resolver,
                        fileUri = fileUri,
                        mediaType = mimeType.toMediaType(),
                        contentLength = contentLength
                    ) { progress ->
                        onProgress?.invoke(progress)
                    }
                )
                .apply { folder?.let { addFormDataPart("folder", it) } }
                .build()

            val request = Request.Builder()
                .url("$apiBase/storage/upload")
                .headers(Headers.Builder().apply {
                    jwtToken?.let { add("Authorization", "Bearer $it") }
                }.build())
                .post(requestBody)
                .build()

            execute(request)
        } catch (e: Exception) {
            ApiResult.Error("Upload error: ${e.message}", 0)
        }
    }

    /**
     * Upload a submission file via the dedicated submissions upload endpoint.
     * Returns only the R2 object key (relative path), never the public R2 URL.
     */
    suspend fun uploadSubmissionFile(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        folder: String
    ): ApiResult<JsonObject> {
        return try {
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    fileBytes.toRequestBody(mimeType.toMediaType())
                )
                .addFormDataPart("folder", folder)
                .build()

            val request = Request.Builder()
                .url("$apiBase/submissions/upload")
                .headers(Headers.Builder().apply {
                    jwtToken?.let { add("Authorization", "Bearer $it") }
                }.build())
                .post(multipartBody)
                .build()

            execute(request)
        } catch (e: Exception) {
            ApiResult.Error("Upload error: ${e.message}", 0)
        }
    }

    /**
     * Get the proxy URL for viewing a submission file without exposing the R2 bucket URL.
     * The file is served through the backend as a proxy.
     */
    fun getSubmissionFileProxyUrl(submissionId: Long): String {
        return "$apiBase/submissions/$submissionId/file"
    }

    /**
     * Download a submission file from the backend proxy (authenticated).
     * Returns the raw file bytes on success.
     */
    suspend fun downloadSubmissionFile(submissionId: Long): ApiResult<ByteArray> {
        return try {
            val request = Request.Builder()
                .url("$apiBase/submissions/$submissionId/file")
                .headers(Headers.Builder().apply {
                    jwtToken?.let { add("Authorization", "Bearer $it") }
                }.build())
                .get()
                .build()

            withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    ApiResult.Success(bytes)
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    ApiResult.Error("Download failed: $errorBody", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Download error: ${e.message}", 0)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // FILE CONTEXTS
    // ═══════════════════════════════════════════════════════════

    suspend fun getFileContexts(): ApiResult<List<FileContext>> =
        executeList(get("/file-contexts"))

    suspend fun getFileContextsBySubmission(submissionId: Long): ApiResult<List<FileContext>> =
        executeList(get("/file-contexts/submission/$submissionId"))

    suspend fun getFileContextById(id: Long): ApiResult<FileContext> =
        execute(get("/file-contexts/$id"))

    suspend fun createFileContext(fileContext: Map<String, Any?>): ApiResult<FileContext> =
        execute(post("/file-contexts", fileContext))

    suspend fun deleteFileContext(id: Long): ApiResult<JsonObject> =
        execute(delete("/file-contexts/$id"))

    // ═══════════════════════════════════════════════════════════
    // REINFORCEMENT (Question Sessions / History)
    // ═══════════════════════════════════════════════════════════

    suspend fun saveReinforcementSession(
        userId: Long,
        courseId: Long,
        questions: List<Map<String, Any?>>,
        topicId: Long? = null,
        taskId: Long? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "userId" to userId,
            "courseId" to courseId,
            "questions" to questions
        )
        if (topicId != null && topicId > 0) body["topicId"] = topicId
        if (taskId != null && taskId > 0) body["taskId"] = taskId
        return execute(post("/reinforcement/save-questions", body))
    }

    suspend fun getReinforcementHistory(courseId: Long): ApiResult<List<JsonObject>> =
        executeList(get("/reinforcement/history/course/$courseId"))

    suspend fun getAllReinforcementHistory(): ApiResult<List<JsonObject>> =
        executeList(get("/reinforcement/history"))

    /**
     * Get existing questions for a user+course+topic+task to enable client-side dedup
     * before generating new questions via LLM.
     * Returns full question objects (question, options, correctIndex, explanation).
     */
    suspend fun getExistingQuestions(
        courseId: Long,
        topicId: Long? = null,
        taskId: Long? = null
    ): ApiResult<JsonObject> {
        val params = mutableListOf("courseId=$courseId")
        if (topicId != null && topicId > 0) params.add("topicId=$topicId")
        if (taskId != null && taskId > 0) params.add("taskId=$taskId")
        val queryString = params.joinToString("&")
        return execute(get("/reinforcement/existing-questions?$queryString"))
    }

    /**
     * Get raw text content from rag_documents for a task/topic/course.
     * Used to build RAG-grounded prompts that prevent LLM hallucination.
     * Returns { content: string, chunks: number, files: string[] }
     */
    suspend fun getRagContent(
        courseId: Long,
        topicId: Long? = null,
        taskId: Long? = null
    ): ApiResult<JsonObject> {
        val params = mutableListOf("courseId=$courseId")
        if (topicId != null && topicId > 0) params.add("topicId=$topicId")
        if (taskId != null && taskId > 0) params.add("taskId=$taskId")
        val queryString = params.joinToString("&")
        return execute(get("/reinforcement/rag-content?$queryString"))
    }

    // ═══════════════════════════════════════════════════════════
    // Learning Context (Contexto unificado para generación de preguntas)
    // ═══════════════════════════════════════════════════════════

    /**
     * Obtiene TODO el contexto de aprendizaje en una sola llamada:
     *   - Detalles del tema (título, descripción)
     *   - Detalles de la tarea (título, descripción)
     *   - Contenido RAG procesado (texto completo de archivos)
     *   - Archivos RAG fuente
     *   - Preguntas existentes para deduplicación
     *   - Metadata de content_items
     *
     * Reemplaza las llamadas separadas a getTopicsByCourse, getTasksByTopic,
     * getContentItemsByTask, ingestTaskContent, getRagContent y getExistingQuestions.
     *
     * @param courseId ID del curso
     * @param topicId ID del tema (opcional)
     * @param taskId ID de la tarea (opcional)
     * @return ApiResult<JsonObject> con todo el contexto estructurado
     */
    suspend fun getLearningContext(
        courseId: Long,
        topicId: Long? = null,
        taskId: Long? = null,
        sessionIndex: Int? = null,
        query: String? = null,
        retrievalMode: String? = null
    ): ApiResult<JsonObject> {
        val params = mutableListOf("courseId=$courseId")
        if (topicId != null && topicId > 0) params.add("topicId=$topicId")
        if (taskId != null && taskId > 0) params.add("taskId=$taskId")
        if (sessionIndex != null && sessionIndex >= 0) params.add("sessionIndex=$sessionIndex")
        if (!query.isNullOrBlank()) params.add("query=${java.net.URLEncoder.encode(query, "UTF-8")}")
        if (!retrievalMode.isNullOrBlank()) params.add("retrievalMode=$retrievalMode")
        val queryString = params.joinToString("&")
        return execute(get("/reinforcement/learning-context?$queryString"))
    }

    // ═══════════════════════════════════════════════════════════
    // RAG (Document Ingestion)
    // ═══════════════════════════════════════════════════════════

    /**
     * Trigger RAG ingestion for a task's content_items.
     * The backend fetches content_items by taskId, downloads files from their URLs,
     * chunks, creates embeddings, and stores in rag_documents.
     * If rag_documents already exist for this task+topic, it skips (dedup).
     *
     * @return ApiResult with ingestion details (skipped, filesProcessed, totalChunksStored)
     */
    suspend fun ingestTaskContent(
        taskId: Long,
        topicId: Long? = null,
        courseId: Long? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>("taskId" to taskId)
        if (topicId != null && topicId > 0) body["topicId"] = topicId
        if (courseId != null && courseId > 0) body["courseId"] = courseId
        return execute(post("/rag/ingest-task-content", body))
    }

    // ═══════════════════════════════════════════════════════════
    // EXCEL / REPORTING
    // ═══════════════════════════════════════════════════════════

    suspend fun generateExcel(query: String, userId: String? = null): ApiResult<JsonObject?> {
        val payload = mapOf("query" to query, "userId" to (userId ?: currentUserId.toString()))
        return execute(post("/excel/generate-excel", payload))
    }

    // ═══════════════════════════════════════════════════════════
    // CHAT MESSAGES
    // ═══════════════════════════════════════════════════════════

    suspend fun upsertChatMessage(chatMessage: ChatMessage): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "message" to chatMessage.message,
            "isFromUser" to chatMessage.isFromUser,
            "timestamp" to chatMessage.timestamp,
            "sessionId" to chatMessage.sessionId,
            "hasCalification" to chatMessage.hasCalification,
            "calificationValue" to chatMessage.calificationValue,
            "calificationAdded" to chatMessage.calificationAdded,
            "username" to chatMessage.senderUsername
        )
        // Do NOT send local Room DB id — the backend column is GENERATED ALWAYS AS IDENTITY
        return execute(post("/chat-messages/upsert", body))
    }

    suspend fun getChatMessagesBySession(sessionId: String): ApiResult<List<JsonObject>> =
        executeList(get("/chat-messages/session/$sessionId"))

    suspend fun getMyChatMessages(): ApiResult<List<JsonObject>> =
        executeList(get("/chat-messages/my"))

    suspend fun deleteChatMessage(id: Long): ApiResult<JsonObject> =
        execute(delete("/chat-messages/$id"))

    suspend fun deleteChatSession(sessionId: String): ApiResult<JsonObject> =
        execute(delete("/chat-messages/session/$sessionId"))

    // ═══════════════════════════════════════════════════════════
    // WHATSAPP
    // ═══════════════════════════════════════════════════════════

    suspend fun linkWhatsApp(phoneNumber: String): ApiResult<JsonObject> =
        execute(post("/whatsapp/link", mapOf("phoneNumber" to phoneNumber)))

    suspend fun verifyWhatsApp(code: String): ApiResult<JsonObject> =
        execute(post("/whatsapp/verify", mapOf("code" to code)))

    suspend fun unlinkWhatsApp(): ApiResult<JsonObject> =
        execute(post("/whatsapp/unlink", null))

    suspend fun getWhatsAppStatus(): ApiResult<JsonObject> =
        execute(get("/whatsapp/status"))
}

// ═══════════════════════════════════════════════════════════
// Result wrapper
// ═══════════════════════════════════════════════════════════

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorMessage(): String = (this as? Error)?.message ?: ""

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): ApiResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (String, Int) -> Unit): ApiResult<T> {
        if (this is Error) action(message, code)
        return this
    }
}
