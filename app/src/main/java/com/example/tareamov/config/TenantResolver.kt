package com.example.tareamov.config

import android.content.Context
import android.util.Log
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Resolves which tenant a user belongs to by calling the cross-tenant
 * authentication endpoint on the main backend. The backend searches
 * ALL tenant databases in a single call.
 *
 * Follows OCP: adding a new tenant only requires backend config changes.
 * Follows SRP: this class only handles tenant resolution logic.
 */
object TenantResolver {
    private const val TAG = "TenantResolver"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Main backend URL that hosts the cross-tenant endpoint. */
    private val MAIN_SERVER_URL = TenantManager.tenants.firstOrNull()?.serverUrl
        ?: "https://mcp-backenddeploy-production-4ed0.up.railway.app"

    private val resolverClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    data class ResolvedLogin(
        val tenant: TenantConfig,
        val authJson: JsonObject
    )

    /** Result when the user exists on more than one tenant. */
    sealed class ResolveResult {
        data class Single(val resolved: ResolvedLogin) : ResolveResult()
        data class Multiple(val matches: List<ResolvedLogin>) : ResolveResult()
        data class None(val message: String) : ResolveResult()
    }

    /**
     * Attempts login on all tenants via the cross-tenant endpoint.
     * Returns Single when found on exactly one, Multiple when found on several,
     * or None when credentials are invalid everywhere.
     */
    suspend fun resolveAndLogin(
        context: Context,
        username: String,
        password: String
    ): ApiResult<BackendApiService.AuthResponse> {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }.toString()
        return when (val result = callCrossTenantEndpoint(context, "/auth/cross-tenant-login", body)) {
            is ResolveResult.Single -> {
                TenantManager.selectTenant(context, result.resolved.tenant.id)
                BackendApiService.login(username, password)
            }
            is ResolveResult.Multiple -> ApiResult.Error("MULTIPLE_TENANTS", 300)
            is ResolveResult.None -> ApiResult.Error(result.message, 401)
        }
    }

    /**
     * Multi-match aware login probe. Returns ResolveResult.
     */
    suspend fun probeLogin(
        context: Context,
        username: String,
        password: String,
        cedula: String? = null
    ): ResolveResult {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
            cedula?.takeIf { it.isNotBlank() }?.let { addProperty("cedula", it) }
        }.toString()
        return callCrossTenantEndpoint(context, "/auth/cross-tenant-login", body)
    }

    /**
     * Completes login after the user picks a specific tenant from the dialog.
     */
    suspend fun commitAndLogin(
        context: Context,
        resolved: ResolvedLogin,
        username: String,
        password: String
    ): ApiResult<BackendApiService.AuthResponse> {
        TenantManager.selectTenant(context, resolved.tenant.id)
        return BackendApiService.login(username, password)
    }

    /**
     * Attempts Google login on all tenants via the cross-tenant endpoint.
     */
    suspend fun resolveAndLoginWithGoogle(
        context: Context,
        email: String,
        displayName: String?,
        avatarUrl: String?,
        usernameHint: String? = null
    ): ApiResult<BackendApiService.AuthResponse> {
        val json = JsonObject().apply {
            addProperty("email", email)
            displayName?.let { addProperty("displayName", it) }
            avatarUrl?.let { addProperty("avatarUrl", it) }
            usernameHint?.let { addProperty("username", it) }
        }
        return when (val result = callCrossTenantEndpoint(context, "/auth/cross-tenant-google", json.toString())) {
            is ResolveResult.Single -> {
                TenantManager.selectTenant(context, result.resolved.tenant.id)
                BackendApiService.loginWithGoogle(email, displayName, avatarUrl, usernameHint)
            }
            is ResolveResult.Multiple -> ApiResult.Error("MULTIPLE_TENANTS", 300)
            is ResolveResult.None -> ApiResult.Error("Usuario no encontrado en ninguna institución", 404)
        }
    }

    /**
     * Multi-match aware Google probe. Returns ResolveResult.
     */
    suspend fun probeGoogleLogin(
        context: Context,
        email: String,
        displayName: String?,
        avatarUrl: String?,
        usernameHint: String? = null
    ): ResolveResult {
        val json = JsonObject().apply {
            addProperty("email", email)
            displayName?.let { addProperty("displayName", it) }
            avatarUrl?.let { addProperty("avatarUrl", it) }
            usernameHint?.let { addProperty("username", it) }
        }
        return callCrossTenantEndpoint(context, "/auth/cross-tenant-google", json.toString())
    }

    /**
     * Completes Google login after user picks a tenant.
     */
    suspend fun commitAndLoginWithGoogle(
        context: Context,
        resolved: ResolvedLogin,
        email: String,
        displayName: String?,
        avatarUrl: String?,
        usernameHint: String? = null
    ): ApiResult<BackendApiService.AuthResponse> {
        TenantManager.selectTenant(context, resolved.tenant.id)
        return BackendApiService.loginWithGoogle(email, displayName, avatarUrl, usernameHint)
    }

    /**
     * Core: calls the cross-tenant endpoint on the main backend.
     * The backend searches ALL tenant databases and returns matches.
     */
    private suspend fun callCrossTenantEndpoint(
        context: Context,
        path: String,
        jsonBody: String
    ): ResolveResult = withContext(Dispatchers.IO) {
        val url = "${MAIN_SERVER_URL.trimEnd('/')}/api/v1$path"
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()

        try {
            val response = resolverClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext ResolveResult.None("Sin respuesta del servidor")

            val parsed = JsonParser.parseString(body)
            if (!parsed.isJsonObject) return@withContext ResolveResult.None("Respuesta inválida")

            val obj = parsed.asJsonObject
            val success = obj.get("success")?.asBoolean ?: false

            if (!success) {
                val errorMsg = obj.getAsJsonObject("error")
                    ?.get("message")?.asString ?: "Credenciales inválidas"
                return@withContext ResolveResult.None(errorMsg)
            }

            val data = obj.getAsJsonObject("data") ?: return@withContext ResolveResult.None("Respuesta vacía")

            // Backend returns { multipleMatches: true, matches: [...] } for multiple
            val isMultiple = data.get("multipleMatches")?.asBoolean ?: false
            if (isMultiple) {
                val matchesArray = data.getAsJsonArray("matches") ?: return@withContext ResolveResult.None("Sin matches")
                val matches = matchesArray.mapNotNull { element ->
                    parseMatchToResolved(element.asJsonObject)
                }
                return@withContext if (matches.isEmpty()) {
                    ResolveResult.None("Credenciales inválidas")
                } else {
                    ResolveResult.Multiple(matches)
                }
            }

            // Single match: data itself is the match
            val resolved = parseMatchToResolved(data)
                ?: return@withContext ResolveResult.None("Credenciales inválidas")

            Log.i(TAG, "User resolved to tenant: ${resolved.tenant.name}")
            ResolveResult.Single(resolved)
        } catch (e: Exception) {
            Log.e(TAG, "Cross-tenant endpoint failed: ${e.message}")
            ResolveResult.None("Error de conexión: ${e.message}")
        }
    }

    /**
     * Parses a JSON match object into a ResolvedLogin, resolving the TenantConfig
     * from TenantManager by tenant.id.
     */
    private fun parseMatchToResolved(matchJson: JsonObject): ResolvedLogin? {
        val tenantObj = matchJson.getAsJsonObject("tenant") ?: return null
        val tenantId = tenantObj.get("id")?.asString ?: return null
        val token = matchJson.get("accessToken")?.asString
        if (token.isNullOrBlank()) return null

        val tenantConfig = TenantManager.tenants.find { it.id == tenantId }
            ?: TenantConfig(
                id = tenantId,
                name = tenantObj.get("name")?.asString ?: "Desconocida",
                serverUrl = MAIN_SERVER_URL,
                supabaseProjectId = ""
            )

        return ResolvedLogin(tenantConfig, matchJson)
    }
}
