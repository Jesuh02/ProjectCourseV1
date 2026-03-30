package com.example.tareamov.config

/**
 * Multi-tenant configuration.
 * Maps each institution to its backend server URL and Supabase project ID.
 * NOTE: Only public/anon keys are referenced. Service role keys live in backend env vars.
 */
data class TenantConfig(
    val id: String,
    val name: String,
    val serverUrl: String,
    val supabaseProjectId: String
)

object TenantManager {
    private const val PREFS_NAME = "tenant_prefs"
    private const val KEY_TENANT_ID = "selected_tenant_id"

    val tenants = listOf(
        TenantConfig(
            id = "qa-develop",
            name = "QA - Develop",
            serverUrl = "https://mcp-backenddeploy-production-4ed0.up.railway.app",
            supabaseProjectId = "vxuksizvwrkctrvpciyp"
        ),
        TenantConfig(
            id = "incat",
            name = "Politécnico Institucional del Caribe Incat",
            serverUrl = "https://mcp-backenddeploy-production.up.railway.app",
            supabaseProjectId = "gzckzrocfmhsizlrnqep"
        )
    )

    private var cachedTenant: TenantConfig? = null

    fun getSelectedTenant(context: android.content.Context): TenantConfig? {
        if (cachedTenant != null) return cachedTenant
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_TENANT_ID, null) ?: return null
        cachedTenant = tenants.find { it.id == id }
        return cachedTenant
    }

    fun selectTenant(context: android.content.Context, tenantId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TENANT_ID, tenantId).apply()
        cachedTenant = tenants.find { it.id == tenantId }
    }

    fun clearTenant(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_TENANT_ID).apply()
        cachedTenant = null
    }

    fun getSelectedServerUrl(context: android.content.Context): String? {
        return getSelectedTenant(context)?.serverUrl
    }
}
