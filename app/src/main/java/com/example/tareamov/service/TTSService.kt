package com.example.tareamov.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * TTSService - Text-to-Speech Service for Android
 * 
 * Provides natural human-like voice synthesis by connecting to the backend TTS API
 * which uses OpenAI's TTS (Text-to-Speech) with voices like "nova", "alloy", etc.
 * 
 * Features:
 * - Natural human voices (not robotic)
 * - Caching of audio files for offline playback
 * - Background audio playback
 * - Playback controls (play, pause, stop)
 */
class TTSService(private val context: Context) {
    
    companion object {
        private const val TAG = "TTSService"
        private const val CACHE_DIR = "tts_cache"
        private const val MAX_CACHE_SIZE_MB = 50
        
        // Singleton instance
        @Volatile
        private var instance: TTSService? = null
        
        fun getInstance(context: Context): TTSService {
            return instance ?: synchronized(this) {
                instance ?: TTSService(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Available voices
    enum class Voice(val id: String, val description: String) {
        ALLOY("alloy", "Neutral, balanced"),
        ECHO("echo", "Male, warm"),
        FABLE("fable", "British accent"),
        ONYX("onyx", "Deep male"),
        NOVA("nova", "Female, friendly"),
        SHIMMER("shimmer", "Female, soft")
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingText: String? = null
    private var isPlaying = false
    
    // Listeners
    private var onPlaybackStartListener: (() -> Unit)? = null
    private var onPlaybackCompleteListener: (() -> Unit)? = null
    private var onPlaybackErrorListener: ((String) -> Unit)? = null
    
    /**
     * Set playback listeners
     */
    fun setOnPlaybackStartListener(listener: () -> Unit) {
        onPlaybackStartListener = listener
    }
    
    fun setOnPlaybackCompleteListener(listener: () -> Unit) {
        onPlaybackCompleteListener = listener
    }
    
    fun setOnPlaybackErrorListener(listener: (String) -> Unit) {
        onPlaybackErrorListener = listener
    }
    
    /**
     * Speak text using TTS
     * @param text Text to speak
     * @param voice Voice to use (default: NOVA for natural female voice)
     * @param onStart Called when playback starts
     * @param onComplete Called when playback completes
     * @param onError Called on error
     */
    suspend fun speak(
        text: String,
        voice: Voice = Voice.NOVA,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        try {
            // Stop any current playback
            stopPlayback()
            
            currentPlayingText = text
            
            // Check cache first
            val cacheKey = generateCacheKey(text, voice)
            val cachedFile = getCachedFile(cacheKey)
            
            val audioFile = if (cachedFile != null && cachedFile.exists()) {
                Log.d(TAG, "Using cached audio file")
                cachedFile
            } else {
                Log.d(TAG, "Requesting TTS from server...")
                val audioData = requestTTS(text, voice)
                if (audioData != null) {
                    saveToCache(cacheKey, audioData)
                } else {
                    withContext(Dispatchers.Main) {
                        onError?.invoke("Failed to generate speech")
                        onPlaybackErrorListener?.invoke("Failed to generate speech")
                    }
                    return@withContext
                }
            }
            
            // Play audio
            withContext(Dispatchers.Main) {
                playAudioFile(audioFile, onStart, onComplete, onError)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                onError?.invoke(e.message ?: "Unknown error")
                onPlaybackErrorListener?.invoke(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Request TTS from backend server
     */
    private suspend fun requestTTS(text: String, voice: Voice): File? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = ServerEndpointResolver.getMcpBaseUrl()
            val url = "$baseUrl/tts/synthesize"
            
            val jsonBody = JSONObject().apply {
                put("text", text)
                put("voice", voice.id)
                put("model", "tts-1-hd") // High quality
                put("format", "mp3")
                put("speed", 1.0)
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()
            
            Log.d(TAG, "Requesting TTS: $url")
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "TTS request failed: ${response.code}")
                return@withContext null
            }
            
            val responseBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(responseBody)
            
            if (!json.optBoolean("success", false)) {
                Log.e(TAG, "TTS error: ${json.optString("error", "Unknown error")}")
                return@withContext null
            }
            
            // Decode base64 audio
            val audioBase64 = json.getString("audio")
            val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
            
            // Save to temp file
            val tempFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
            }
            
            Log.d(TAG, "TTS audio received: ${audioBytes.size} bytes")
            return@withContext tempFile
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS request error: ${e.message}", e)
            return@withContext null
        }
    }
    
    /**
     * Play audio file
     */
    private fun playAudioFile(
        file: File,
        onStart: (() -> Unit)?,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        try {
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                
                setDataSource(file.absolutePath)
                
                setOnPreparedListener {
                    this@TTSService.isPlaying = true
                    start()
                    onStart?.invoke()
                    onPlaybackStartListener?.invoke()
                    Log.d(TAG, "TTS playback started")
                }
                
                setOnCompletionListener {
                    this@TTSService.isPlaying = false
                    this@TTSService.currentPlayingText = null
                    onComplete?.invoke()
                    onPlaybackCompleteListener?.invoke()
                    Log.d(TAG, "TTS playback completed")
                }
                
                setOnErrorListener { _, what, extra ->
                    this@TTSService.isPlaying = false
                    this@TTSService.currentPlayingText = null
                    val errorMsg = "Playback error: $what, $extra"
                    Log.e(TAG, errorMsg)
                    onError?.invoke(errorMsg)
                    onPlaybackErrorListener?.invoke(errorMsg)
                    true
                }
                
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play error: ${e.message}", e)
            onError?.invoke(e.message ?: "Playback error")
            onPlaybackErrorListener?.invoke(e.message ?: "Playback error")
        }
    }
    
    /**
     * Stop current playback
     */
    fun stopPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            isPlaying = false
            currentPlayingText = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop error: ${e.message}", e)
        }
    }
    
    /**
     * Pause playback
     */
    fun pausePlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    isPlaying = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pause error: ${e.message}", e)
        }
    }
    
    /**
     * Resume playback
     */
    fun resumePlayback() {
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    isPlaying = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resume error: ${e.message}", e)
        }
    }
    
    /**
     * Check if currently playing
     */
    fun isCurrentlyPlaying(): Boolean = isPlaying
    
    /**
     * Get current playing text
     */
    fun getCurrentPlayingText(): String? = currentPlayingText
    
    /**
     * Toggle playback (play/pause)
     */
    fun togglePlayback(): Boolean {
        return if (isPlaying) {
            pausePlayback()
            false
        } else {
            resumePlayback()
            true
        }
    }
    
    // Cache management
    
    private fun generateCacheKey(text: String, voice: Voice): String {
        val hash = text.hashCode().toString(16)
        return "${voice.id}_$hash"
    }
    
    private fun getCacheDir(): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    private fun getCachedFile(cacheKey: String): File? {
        val file = File(getCacheDir(), "$cacheKey.mp3")
        return if (file.exists()) file else null
    }
    
    private fun saveToCache(cacheKey: String, tempFile: File): File {
        val cacheFile = File(getCacheDir(), "$cacheKey.mp3")
        tempFile.copyTo(cacheFile, overwrite = true)
        tempFile.delete()
        
        // Clean old cache if needed
        cleanCacheIfNeeded()
        
        return cacheFile
    }
    
    private fun cleanCacheIfNeeded() {
        try {
            val cacheDir = getCacheDir()
            val files = cacheDir.listFiles() ?: return
            
            var totalSize = files.sumOf { it.length() }
            val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024L
            
            if (totalSize > maxSize) {
                // Sort by last modified (oldest first)
                val sortedFiles = files.sortedBy { it.lastModified() }
                
                for (file in sortedFiles) {
                    if (totalSize <= maxSize / 2) break
                    totalSize -= file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache cleanup error: ${e.message}", e)
        }
    }
    
    /**
     * Clear all cached audio files
     */
    fun clearCache() {
        try {
            getCacheDir().listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Clear cache error: ${e.message}", e)
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        stopPlayback()
    }
}
