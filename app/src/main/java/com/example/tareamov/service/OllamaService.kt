package com.example.tareamov.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.util.NetworkUtils

/**
 * Service to manage Ollama LLM initialization and connection
 */
class OllamaService : Service() {
    private val TAG = "OllamaService"
    private val isRunning = AtomicBoolean(false)
    private lateinit var mspClient: MSPClient
    private val maxRetries = 5 // Aumentamos el número de reintentos
    private val retryDelayMs = 0 // 3 segundos entre reintentos
    private lateinit var database: AppDatabase

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OllamaService created")
        mspClient = MSPClient(this) // Initialize with context
        database = AppDatabase.getDatabase(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OllamaService started")

        if (!isRunning.getAndSet(true)) {
            CoroutineScope(Dispatchers.IO).launch {
                // Try to start Ollama if it's not already running
                tryStartOllama()

                // Then initialize the connection
                initializeOllama()
            }
        }

        return START_STICKY
    }

    private suspend fun tryStartOllama() {
        try {
            // Obtener URLs dinámicamente basadas en la IP del dispositivo
            val dynamicUrls = NetworkUtils.buildServerUrls(this, 11435)
            Log.d(TAG, "🔍 Buscando Ollama en ${dynamicUrls.size} direcciones dinámicas")
            
            for (url in dynamicUrls) {
                val displayAddress = url.removePrefix("http://").removeSuffix(":11435")
                if (mspClient.isServerRunning(url)) {
                    Log.d(TAG, "✅ Ollama encontrado en $displayAddress")
                    
                    // Broadcast que Ollama está corriendo
                    val broadcastIntent = Intent("com.example.tareamov.OLLAMA_STATUS")
                    broadcastIntent.putExtra("status", "running")
                    broadcastIntent.putExtra("message", "Ollama está ejecutándose en $displayAddress")
                    sendBroadcast(broadcastIntent)
                    return
                }
            }

            // Si no se encontró Ollama
            Log.w(TAG, "⚠️ Ollama no está ejecutándose en ninguna dirección detectada")

            // Broadcast que Ollama necesita iniciarse
            val broadcastIntent = Intent("com.example.tareamov.OLLAMA_STATUS")
            broadcastIntent.putExtra("status", "not_running")
            broadcastIntent.putExtra("message", "Ollama no está ejecutándose. Usando modelo local como respaldo.")
            sendBroadcast(broadcastIntent)

            // Esperar un poco para ver si el usuario inicia Ollama
            delay(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error al intentar iniciar Ollama", e)
        }
    }

    private suspend fun initializeOllama() {
        // Try multiple times to connect to Ollama
        var retryCount = 0
        var connected = false

        while (retryCount < maxRetries && !connected) {
            // Check if the local Ollama server is accessible
            val apiStatus = mspClient.isServerRunning()
            Log.d(TAG, "Local Ollama status (attempt ${retryCount + 1}): ${if (apiStatus) "✅ accessible" else "❌ not accessible"}")

            if (apiStatus) {
                connected = true
                // If Ollama is accessible, perform a simple test query to load the model
                try {
                    Log.d(TAG, "Sending test prompt to initialize model...")

                    // Simple test prompt to minimize potential issues
                    val testPrompt = "Responde con 'Modelo llama3 inicializado correctamente'"
                    
                    val testResponse = mspClient.sendPrompt(
                        testPrompt,
                        includeHistory = false,
                        includeDatabaseContext = false
                    )
                    Log.d(TAG, "Model initialization response: $testResponse")

                    // Broadcast that the model is ready
                    val broadcastIntent = Intent("com.example.tareamov.OLLAMA_READY")
                    broadcastIntent.putExtra("status", "ready")
                    broadcastIntent.putExtra("message", "Modelo Llama3 inicializado correctamente")
                    sendBroadcast(broadcastIntent)
                    
                    // REMOVED: No longer building database context in background
                    // This was causing prompt truncation (exceeding 4096 tokens)
                    // Database context should only be sent AFTER LLM requests data via MCP tools
                    /*
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            Log.d(TAG, "Building database context in background...")
                            val context = buildEnhancedDatabaseContext()
                            Log.d(TAG, "Database context built successfully (${context.length} chars)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error building database context in background", e)
                        }
                    }
                    */
                } catch (e: Exception) {
                    Log.e(TAG, "Error during model initialization", e)
                    connected = false
                    retryCount++
                }
            } else {
                // Wait before retrying
                Log.d(TAG, "Waiting before retry...")
                delay(retryDelayMs.toLong() + 1000) // Add a little delay between retries
                retryCount++
            }
        }

        if (!connected) {
            Log.e(TAG, "Failed to connect to Ollama after $maxRetries attempts")
            // Broadcast that the model failed to initialize
            val broadcastIntent = Intent("com.example.tareamov.OLLAMA_READY")
            broadcastIntent.putExtra("status", "failed")
            broadcastIntent.putExtra("message", "No se pudo conectar al servidor Ollama después de $maxRetries intentos")
            sendBroadcast(broadcastIntent)
        }
    }

    /**
     * Build enhanced database context including Task and Subscription data
     */
    private suspend fun buildEnhancedDatabaseContext(): String {
        return try {
            // Use the MSPClient to get the context directly
            val mspClient = MSPClient(this@OllamaService)
            val baseContext = mspClient.buildDatabaseContext()

            val snapshotBuilder = StringBuilder()
            snapshotBuilder.append("\n\n// --- Supabase snapshots generados automáticamente ---\n")
            val snapshotTables = listOf("usuarios", "personas", "courses", "videos", "subscriptions", "task_submissions")

            for (table in snapshotTables) {
                try {
                    val records = SupabaseClient.fetchTableSnapshot(table, 3)
                    if (records.isNotEmpty()) {
                        snapshotBuilder.append("Tabla $table (primeros ${records.size} registros): \n")
                        records.forEach { record ->
                            snapshotBuilder.append("  · ${record}\n")
                        }
                        snapshotBuilder.append("\n")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo obtener snapshot para $table", e)
                }
            }

            baseContext + snapshotBuilder.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error building enhanced database context", e)
            "Error al obtener contexto de base de datos: ${e.message}"
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "OllamaService destroyed")
        isRunning.set(false)
        super.onDestroy()
    }
}