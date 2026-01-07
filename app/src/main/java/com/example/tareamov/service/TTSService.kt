package com.example.tareamov.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * TTSService - Text-to-Speech Service for Android
 * 
 * Provides natural human-like voice synthesis by connecting to the backend TTS API
 * which uses OpenAI's TTS (Text-to-Speech) with voices like "nova", "alloy", etc.
 * Fallback to Android Native TTS if backend is unavailable.
 * 
 * Features:
 * - Natural human voices (not robotic)
 * - Caching of audio files for offline playback
 * - Background audio playback
 * - Playback controls (play, pause, stop)
 * - Native TTS fallback
 */
class TTSService(private val context: Context) : TextToSpeech.OnInitListener {
    
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    // Native TTS
    private var textToSpeech: TextToSpeech? = null
    private var isNativeTTSReady = false
    
    // Current playback callbacks
    private var currentOnStart: (() -> Unit)? = null
    private var currentOnComplete: (() -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null

    init {
        try {
            textToSpeech = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Native TTS", e)
        }
        checkServerAvailability()
    }

    private fun checkServerAvailability() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val baseUrl = ServerEndpointResolver.getMcpBaseUrl()
                val url = "$baseUrl/tts/health"
                val request = Request.Builder().url(url).build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    isServerTTSAvailable = json.optBoolean("available", false)
                    Log.i(TAG, "Server TTS availability checked: $isServerTTSAvailable")
                } else {
                    isServerTTSAvailable = false
                    Log.w(TAG, "Server TTS health check failed: ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server TTS health check error: ${e.message}")
                isServerTTSAvailable = false
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.getDefault())
            isNativeTTSReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (isNativeTTSReady) {
                Log.i(TAG, "Native TTS initialized successfully")
                
                // Set listener once
                textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        currentOnStart?.invoke()
                        onPlaybackStartListener?.invoke()
                    }

                    override fun onDone(utteranceId: String?) {
                        currentOnComplete?.invoke()
                        onPlaybackCompleteListener?.invoke()
                    }

                    override fun onError(utteranceId: String?) {
                        val msg = "Native TTS error (unknown)"
                        Log.e(TAG, msg)
                        currentOnError?.invoke(msg)
                        onPlaybackErrorListener?.invoke(msg)
                    }
                    
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val msg = "Native TTS error: $errorCode"
                        Log.e(TAG, msg)
                        currentOnError?.invoke(msg)
                        onPlaybackErrorListener?.invoke(msg)
                    }
                })
            } else {
                Log.e(TAG, "Native TTS language not supported")
            }
        } else {
            Log.e(TAG, "Native TTS initialization failed")
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
    
    
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingText: String? = null
    private var isPlaying = false
    private var isServerTTSAvailable = true
    
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
     * Sanitize text to remove markdown and emojis
     */
    private fun sanitizeText(text: String): String {
        return text
            .replace("**", "")
            .replace("#", "")
            .replace("`", "")
            // Remove emojis (surrogate pairs)
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
            // Remove misc symbols and dingbats
            .replace(Regex("[\\u2600-\\u27BF]"), "")
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
            // Sanitize text first
            val textToSpeak = sanitizeText(text)
            
            // Stop any current playback
            stopPlayback()
            
            currentPlayingText = textToSpeak
            
            // Check cache first
            val cacheKey = generateCacheKey(textToSpeak, voice)
            val cachedFile = getCachedFile(cacheKey)
            if (cachedFile != null && cachedFile.exists()) {
                Log.d(TAG, "Using cached audio file")
                withContext(Dispatchers.Main) {
                    playAudioFile(cachedFile, onStart, onComplete, onError)
                }
                return@withContext
            }

            // Try streaming (FASTEST METHOD)
            if (isServerTTSAvailable) {
                try {
                    val baseUrl = ServerEndpointResolver.getMcpBaseUrl()
                    val encodedText = java.net.URLEncoder.encode(textToSpeak, "UTF-8")
                    
                    // Use GET streaming for reasonable length texts (URL limit safety)
                    if (encodedText.length < 4000) {
                        val streamUrl = "$baseUrl/tts/stream?text=$encodedText&voice=${voice.id}&format=mp3"
                        Log.d(TAG, "🚀 Streaming TTS from: $streamUrl")
                        
                        withContext(Dispatchers.Main) {
                            playFromStream(streamUrl, onStart, onComplete, onError)
                        }
                        return@withContext
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Streaming setup failed, falling back to download: ${e.message}")
                }
            }

            // Not in cache or too long for stream URL, try download (slower but reliable)
            var audioData: File? = null
            if (isServerTTSAvailable) {
                Log.d(TAG, "Requesting TTS download from server...")
                audioData = requestTTS(textToSpeak, voice)
                if (audioData == null) {
                    Log.w(TAG, "Server TTS failed, disabling for this session")
                    isServerTTSAvailable = false
                }
            }

            if (audioData != null) {
                val savedFile = saveToCache(cacheKey, audioData)
                withContext(Dispatchers.Main) {
                    playAudioFile(savedFile, onStart, onComplete, onError)
                }
            } else {
                Log.w(TAG, "Falling back to native TTS")
                speakNative(textToSpeak, onStart, onComplete, onError)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "TTS error: ${e.message}", e)
            // Try native fallback on general error too if not already tried
            try {
                speakNative(sanitizeText(text), onStart, onComplete, onError)
            } catch (nativeEx: Exception) {
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Unknown error")
                    onPlaybackErrorListener?.invoke(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Speak text IMMEDIATELY using native TTS (no network delay)
     * This provides instant audio feedback to the user.
     * Use this when immediate response is more important than voice quality.
     * 
     * @param text Text to speak
     * @param onStart Called when playback starts
     * @param onComplete Called when playback completes
     * @param onError Called on error
     */
    suspend fun speakImmediate(
        text: String,
        onStart: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.Main) {
        try {
            val textToSpeak = sanitizeText(text)
            stopPlayback()
            currentPlayingText = textToSpeak
            
            Log.d(TAG, "🚀 speakImmediate: Starting native TTS immediately for ${textToSpeak.length} chars")
            
            if (isNativeTTSReady && textToSpeech != null) {
                // Update current callbacks
                currentOnStart = onStart
                currentOnComplete = onComplete
                currentOnError = onError
                
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_immediate")
                
                // Truncate if too long
                val maxLen = 3900
                val finalText = if (textToSpeak.length > maxLen) {
                    textToSpeak.substring(0, maxLen) + "..."
                } else {
                    textToSpeak
                }
                
                val result = textToSpeech?.speak(finalText, TextToSpeech.QUEUE_FLUSH, params, "tts_immediate")
                
                if (result == TextToSpeech.SUCCESS) {
                    isPlaying = true
                    Log.d(TAG, "✅ Native TTS started immediately")
                } else {
                    Log.e(TAG, "Native TTS speak returned error")
                    onError?.invoke("Native TTS failed to start")
                    onPlaybackErrorListener?.invoke("Native TTS failed to start")
                }
            } else {
                Log.w(TAG, "Native TTS not ready, falling back to regular speak")
                // Fall back to regular speak if native TTS not ready
                withContext(Dispatchers.IO) {
                    speak(text, Voice.NOVA, onStart, onComplete, onError)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "speakImmediate error: ${e.message}", e)
            onError?.invoke(e.message ?: "TTS error")
            onPlaybackErrorListener?.invoke(e.message ?: "TTS error")
        }
    }

    /**
     * Play audio directly from stream URL
     */
    private fun playFromStream(
        url: String,
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
                
                setDataSource(url)
            
                // Stop native TTS
                if (textToSpeech?.isSpeaking == true) {
                    textToSpeech?.stop()
                }
            
                setOnPreparedListener {
                    this@TTSService.isPlaying = true
                    start()
                    onStart?.invoke()
                    onPlaybackStartListener?.invoke()
                    Log.d(TAG, "TTS streaming started")
                }
                
                setOnCompletionListener {
                    this@TTSService.isPlaying = false
                    this@TTSService.currentPlayingText = null
                    onComplete?.invoke()
                    onPlaybackCompleteListener?.invoke()
                    Log.d(TAG, "TTS streaming completed")
                }
                
                setOnErrorListener { _, what, extra ->
                    this@TTSService.isPlaying = false
                    this@TTSService.currentPlayingText = null
                    val errorMsg = "Streaming error: $what, $extra"
                    Log.e(TAG, errorMsg)
                    onError?.invoke(errorMsg)
                    onPlaybackErrorListener?.invoke(errorMsg)
                    true
                }
                
                prepareAsync() // Prepare asynchronously for streaming
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream play error: ${e.message}", e)
            onError?.invoke(e.message ?: "Streaming error")
            onPlaybackErrorListener?.invoke(e.message ?: "Streaming error")
        }
    }

    private suspend fun speakNative(
        text: String,
        onStart: (() -> Unit)?,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) = withContext(Dispatchers.Main) {
        if (isNativeTTSReady && textToSpeech != null) {
            try {
                // Update current callbacks
                currentOnStart = onStart
                currentOnComplete = onComplete
                currentOnError = onError
                
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_utterance")
                
                // Check length limit (approx 4000 chars)
                val maxLen = 3900
                val textToSpeak = if (text.length > maxLen) {
                    Log.w(TAG, "Text too long for TTS (${text.length}), truncating to $maxLen")
                    text.substring(0, maxLen) + "..."
                } else {
                    text
                }

                val result = textToSpeech?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "tts_utterance")
                
                if (result == TextToSpeech.ERROR) {
                    Log.e(TAG, "Native TTS speak returned ERROR")
                    onError?.invoke("Native TTS failed to start")
                } else {
                    currentPlayingText = text
                    isPlaying = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Native TTS speak error", e)
                onError?.invoke("Native TTS failed")
            }
        } else {
            Log.e(TAG, "Native TTS not ready")
            onError?.invoke("TTS service unavailable")
            onPlaybackErrorListener?.invoke("TTS service unavailable")
        }
    }
    
    /**
     * Request TTS from backend server
     */
    private suspend fun requestTTS(text: String, voice: Voice): File? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = ServerEndpointResolver.getMcpBaseUrl()
            val url = "$baseUrl/tts/synthesize"
            
            Log.d(TAG, "Requesting TTS from: $url")
            Log.d(TAG, "Text length: ${text.length}, Voice: ${voice.id}")
            
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
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "TTS request failed: ${response.code} - $errorBody")
                return@withContext null
            }
            
            val responseBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(responseBody)
            
            if (!json.optBoolean("success", false)) {
                val error = json.optString("error", "Unknown error")
                Log.e(TAG, "TTS API error: $error")
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
            
            Log.d(TAG, "TTS audio received and saved: ${tempFile.length()} bytes")
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
            
            // Stop native TTS
            if (textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
            }
            
                
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
            // Stop Native TTS
            if (textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
            }

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
            // Native TTS cannot be paused, so we stop it but keep the text to restart later
            if (textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
                isPlaying = false
                // Do not clear currentPlayingText so we can resume (restart)
                return
            }

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
            // If Native TTS was "paused" (stopped), restart it
            if (mediaPlayer == null && currentPlayingText != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    speakNative(currentPlayingText!!, currentOnStart, currentOnComplete, currentOnError)
                }
                return
            }

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
        textToSpeech?.shutdown()
    }
}
