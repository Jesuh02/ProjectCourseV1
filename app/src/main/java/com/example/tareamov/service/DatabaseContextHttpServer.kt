package com.example.tareamov.service

import android.util.Log
import com.example.tareamov.data.AppDatabase
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

class DatabaseContextHttpServer(
    private val context: android.content.Context, // Add this line
    private val database: AppDatabase,
    port: Int = 8081
) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/database/context" -> {
                try {
                    val dbService = DatabaseQueryService(context)
                    val json = runBlocking { dbService.generateDatabaseJson() }
                    Log.d("DatabaseContextHttpServer", "Enviando JSON al modelo: $json")
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json)
                } catch (e: Exception) {
                    Log.e("DatabaseContextHttpServer", "Error serving context", e)
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}