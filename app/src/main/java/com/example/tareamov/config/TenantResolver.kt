package com.example.tareamov.config

import android.content.Context
import android.util.Log
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** Returns deduplicated list of all backend server URLs from tenant config. */
    private fun getDistinctServerUrls(): List<String> =
        TenantManager.tenants.map { it.serverUrl.trimEnd('/') }.distinct()

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
                // Delegate to commitAndLogin so the probe token is reused
                commitAndLogin(context, result.resolved, username, password)
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
     * Reuses the token already obtained during probeLogin — avoids a second network
     * round-trip and the need to re-verify credentials on the regular /auth/login
     * endpoint (which only searches the primary database and would fail when the
     * user was found in a secondary database via the cross-tenant probe).
     */
    suspend fun commitAndLogin(
        context: Context,
        resolved: ResolvedLogin,
        username: String,
        password: String
    ): ApiResult<BackendApiService.AuthResponse> {
        TenantManager.selectTenant(context, resolved.tenant.id)

        // Build AuthResponse from the probe result (credentials already verified)
        val accessToken = resolved.authJson.get("accessToken")?.takeIf { !it.isJsonNull }?.asString
        val refreshToken = resolved.authJson.get("refreshToken")?.takeIf { !it.isJsonNull }?.asString
        val userObj = resolved.authJson.getAsJsonObject("user")

        if (!accessToken.isNullOrBlank() && userObj != null) {
            val authResponse = BackendApiService.AuthResponse(
                accessToken = accessToken,
                refreshToken = refreshToken,
                user = userObj
            )
            BackendApiService.storeAuthResult(authResponse)
            Log.i(TAG, "commitAndLogin: reusing probe token for tenant ${resolved.tenant.id}")
            return ApiResult.Success(authResponse)
        }

        // Fallback: probe result lacked a token — attempt direct login
        Log.w(TAG, "commitAndLogin: probe token missing, falling back to direct login for ${resolved.tenant.id}")
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
                // Delegate to commitAndLoginWithGoogle so the probe token is reused
                commitAndLoginWithGoogle(context, result.resolved, email, displayName, avatarUrl, usernameHint)
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
     * Reuses the token already obtained during probeGoogleLogin — avoids a second
     * network call and the cross-database search limitation of the regular endpoint.
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

        val accessToken = resolved.authJson.get("accessToken")?.takeIf { !it.isJsonNull }?.asString
        val refreshToken = resolved.authJson.get("refreshToken")?.takeIf { !it.isJsonNull }?.asString
        val userObj = resolved.authJson.getAsJsonObject("user")

        if (!accessToken.isNullOrBlank() && userObj != null) {
            val authResponse = BackendApiService.AuthResponse(
                accessToken = accessToken,
                refreshToken = refreshToken,
                user = userObj
            )
            BackendApiService.storeAuthResult(authResponse)
            Log.i(TAG, "commitAndLoginWithGoogle: reusing probe token for tenant ${resolved.tenant.id}")
            return ApiResult.Success(authResponse)
        }

        Log.w(TAG, "commitAndLoginWithGoogle: probe token missing, falling back to direct google login for ${resolved.tenant.id}")
        return BackendApiService.loginWithGoogle(email, displayName, avatarUrl, usernameHint)
    }

    /**
     * Core: probes ALL distinct backend servers in parallel (matching frontend behaviour).
     * Each server searches its own registered tenant databases via the cross-tenant endpoint.
     * Results are merged; duplicates (same tenant.id) are deduplicated.
     */
    private suspend fun callCrossTenantEndpoint(
        @Suppress("UNUSED_PARAMETER") context: Context,
        path: String,
        jsonBody: String
    ): ResolveResult = coroutineScope {
        val servers = getDistinctServerUrls()
        val deferreds = servers.map { serverUrl ->
            async(Dispatchers.IO) {
                try {
                    probeSingleServer(serverUrl, path, jsonBody)
                } catch (e: Exception) {
                    Log.w(TAG, "Server $serverUrl failed: ${e.message}")
                    emptyList()
                }
            }
        }
        val allMatches = deferreds.awaitAll().flatten()

        // Deduplicate by tenant id
        val seen = mutableSetOf<String>()
        val unique = allMatches.filter { seen.add(it.tenant.id) }

        return@coroutineScope when {
            unique.isEmpty() -> ResolveResult.None("Credenciales inválidas")
            unique.size == 1 -> {
                Log.i(TAG, "User resolved to tenant: ${unique[0].tenant.name}")
                ResolveResult.Single(unique[0])
            }
            else -> ResolveResult.Multiple(unique)
        }
    }

    /**
     * Calls the cross-tenant endpoint on a single server.
     * Returns a list of resolved logins (may be >1 if the server found multi-tenant matches).
     */
    private fun probeSingleServer(serverUrl: String, path: String, jsonBody: String): List<ResolvedLogin> {
        val url = "${serverUrl}/api/v1$path"
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .build()

        val response = resolverClient.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()

        val parsed = JsonParser.parseString(body)
        if (!parsed.isJsonObject) return emptyList()

        val obj = parsed.asJsonObject
        val success = obj.get("success")?.asBoolean ?: false
        if (!success) return emptyList()

        val data = obj.getAsJsonObject("data") ?: return emptyList()

        // Backend returns { multipleMatches: true, matches: [...] } for multiple
        val isMultiple = data.get("multipleMatches")?.asBoolean ?: false
        if (isMultiple) {
            val matchesArray = data.getAsJsonArray("matches") ?: return emptyList()
            return matchesArray.mapNotNull { parseMatchToResolved(it.asJsonObject, serverUrl) }
        }

        return listOfNotNull(parseMatchToResolved(data, serverUrl))
    }

    /**
     * Parses a JSON match object into a ResolvedLogin, resolving the TenantConfig
     * from TenantManager by tenant.id.
     */
    private fun parseMatchToResolved(matchJson: JsonObject, fallbackServerUrl: String): ResolvedLogin? {
        val tenantObj = matchJson.getAsJsonObject("tenant") ?: return null
        val tenantId = tenantObj.get("id")?.asString ?: return null
        val token = matchJson.get("accessToken")?.asString
        if (token.isNullOrBlank()) return null

        val tenantConfig = TenantManager.tenants.find { it.id == tenantId }
            ?: TenantConfig(
                id = tenantId,
                name = tenantObj.get("name")?.asString ?: "Desconocida",
                serverUrl = fallbackServerUrl,
                supabaseProjectId = ""
            )

        return ResolvedLogin(tenantConfig, matchJson)
    }
}
