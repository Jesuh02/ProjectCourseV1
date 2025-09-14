package com.example.tareamov.data.repository

import com.example.tareamov.BuildConfig

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Minimal Supabase REST client using OkHttp. This avoids pulling a large SDK and keeps control
// over headers. It performs simple upserts to Supabase tables via the PostgREST interface.
class SupabaseRepository(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseKey: String = BuildConfig.SUPABASE_KEY
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Simple function to upsert an object into a table using PostgREST upsert (on conflict) via RPC headers
    // Upsert wrapper: instead of relying on individual tables existing in Supabase,
    // store each entity in a single 'app_documents' table with columns:
    //  - table_name TEXT
    //  - entity_id TEXT
    //  - data JSONB
    // This avoids schema drift between Room entities and Postgres and requires only
    // creating one table on the Supabase side.
    fun upsert(table: String, payload: Any) : Boolean {
        try {
            // Try to extract an identifier from the payload (common keys: id, uuid, usuario)
            val asMap = gson.fromJson(gson.toJson(payload), Map::class.java)
            val possibleId = (asMap["id"] ?: asMap["uuid"] ?: asMap["usuario"] ?: asMap["username"] ?: asMap["email"])?.toString()

            val wrapper = mapOf(
                "table_name" to table,
                "entity_id" to (possibleId ?: java.util.UUID.randomUUID().toString()),
                "data" to asMap
            )

            val url = "${supabaseUrl}/rest/v1/app_documents"
            val bodyJson = gson.toJson(wrapper)
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                // Prefer merge duplicates on conflict if the table has unique constraint on (table_name, entity_id)
                .addHeader("Prefer", "resolution=merge-duplicates")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("SupabaseRepository", "Upsert failed for app_documents code=${resp.code} body=${resp.body?.string()}")
                    return false
                }
                return true
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Exception while upserting to app_documents", e)
            return false
        }
    }

    // Basic function to sign in using the Supabase REST auth endpoint. Returns access token or null.
    suspend fun loginConEmail(email: String, password: String): String? {
        try {
            val url = "${supabaseUrl}/auth/v1/token?grant_type=password"
            val payload = mapOf("email" to email, "password" to password)
            val bodyJson = gson.toJson(payload)
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .addHeader("apikey", supabaseKey)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("SupabaseRepository", "Login failed code=${resp.code} body=${resp.body?.string()}")
                    return null
                }
                val respStr = resp.body?.string() ?: return null
                val tree = gson.fromJson(respStr, Map::class.java)
                val accessToken = tree["access_token"] as? String
                return accessToken
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Login exception", e)
            return null
        }
    }

    // Check if a REST endpoint (table) exists by making a lightweight HEAD-like request
    fun tableExists(table: String): Boolean {
        try {
            val url = "${supabaseUrl}/rest/v1/$table?select=1&limit=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                // 200 means the table exists (even if empty). 404 or 400 likely means not found
                return resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseRepository", "tableExists check failed for $table", e)
            return false
        }
    }
}
