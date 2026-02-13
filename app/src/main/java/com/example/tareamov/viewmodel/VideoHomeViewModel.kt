package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.network.FallbackDnsResolver
import com.example.tareamov.service.network.NetworkConnectivityChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for loading and managing the video feed.
 *
 * Follows SOLID principles:
 *  - SRP: Only manages video feed state (loading, error, data).
 *  - OCP: Retry strategy can be extended without modifying load logic.
 *  - DIP: Depends on BackendApiService abstraction, not concrete HTTP impl.
 *
 * Implements exponential backoff retry to handle transient backend errors
 * (502, timeouts) without overwhelming the server.
 */
class VideoHomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoHomeViewModel"
        private const val MAX_RETRIES = 3
        private const val INITIAL_DELAY_MS = 2_000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val DEFAULT_PAGE_SIZE = 10
    }

    // ── Observable state ─────────────────────────────────────────
    private val _videoList = MutableLiveData<List<VideoData>>(emptyList())
    val videoList: LiveData<List<VideoData>> = _videoList

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _hasError = MutableLiveData(false)
    val hasError: LiveData<Boolean> = _hasError

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    var currentVideoIndex: Int = 0
    var totalVideos: Int = 0
        private set

    private var loadJob: Job? = null

    init {
        BackendApiService.initialize(application.applicationContext)
    }

    // ── Public API ───────────────────────────────────────────────

    /** Checks device connectivity before attempting any network call. */
    fun isDeviceOnline(): Boolean =
        NetworkConnectivityChecker.isNetworkAvailable(getApplication())

    /**
     * Loads the video feed from the backend with automatic retry on failure.
     *
     * @param targetVideoId If > 0, fetches that video first and places it at the top.
     * @param pageSize Number of videos per page.
     * @param isRefresh If true, forces a reload even if data exists.
     */
    fun loadVideos(
        targetVideoId: Long = -1L,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        isRefresh: Boolean = false
    ) {
        // Prevent duplicate concurrent loads
        if (_isLoading.value == true && !isRefresh) return

        // Skip if data is already loaded and no refresh is requested
        if (!isRefresh && _videoList.value?.isNotEmpty() == true && targetVideoId == -1L) return

        // Cancel any in-flight load before starting a new one
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _hasError.value = false
            _errorMessage.value = null

            // Validar conectividad antes de intentar la red
            if (!isDeviceOnline()) {
                _hasError.value = true
                _errorMessage.value = NetworkConnectivityChecker.getOfflineMessage()
                _isLoading.value = false
                return@launch
            }

            // Limpiar cache DNS al refrescar para forzar re-resolución
            if (isRefresh) {
                FallbackDnsResolver.clearCache()
            }

            val result = fetchVideosWithRetry(targetVideoId, pageSize)

            if (result.isNotEmpty()) {
                _videoList.value = result
                totalVideos = if (result.size < pageSize) result.size else result.size + pageSize
                preCacheVideoAssets(result)
            } else {
                _hasError.value = true
            }

            _isLoading.value = false
        }
    }

    /**
     * Loads the next page of videos and appends them to the existing list.
     */
    fun loadMoreVideos(pageSize: Int = DEFAULT_PAGE_SIZE) {
        if (_isLoading.value == true) return

        val currentList = _videoList.value ?: emptyList()
        if (currentList.size >= totalVideos) return

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val nextPage = (currentList.size / pageSize) + 2 // +2 because first load was page 1
                val newVideos = fetchPage(nextPage, pageSize)

                if (newVideos.isNotEmpty()) {
                    val combined = currentList + newVideos
                    _videoList.value = combined
                    totalVideos = if (newVideos.size < pageSize) combined.size else combined.size + pageSize
                    preCacheVideoAssets(newVideos)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more videos", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Retries loading after a failure. Can be called from the UI (e.g. tap-to-retry).
     */
    fun retryLoad() {
        loadVideos(isRefresh = true)
    }

    // ── Private helpers ──────────────────────────────────────────

    /**
     * Fetches videos with exponential backoff retry.
     * Retries up to [MAX_RETRIES] times with increasing delays.
     */
    private suspend fun fetchVideosWithRetry(
        targetVideoId: Long,
        pageSize: Int
    ): List<VideoData> {
        var lastError: String? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                Log.d(TAG, "Loading videos (attempt $attempt/$MAX_RETRIES, target=$targetVideoId)")

                // Fetch target video if requested (non-blocking on failure)
                val targetVideo = if (targetVideoId > 0) fetchTargetVideo(targetVideoId) else null

                // Fetch the main video feed
                val videos = fetchPage(page = 1, pageSize = pageSize)

                // If both failed, retry
                if (videos.isEmpty() && targetVideo == null) {
                    lastError = "No videos returned from backend"
                    if (attempt < MAX_RETRIES) {
                        val delayMs = calculateBackoff(attempt)
                        Log.w(TAG, "Attempt $attempt failed, retrying in ${delayMs}ms...")
                        delay(delayMs)
                        continue
                    }
                    break
                }

                // Compose final list: target video first, then the rest
                return buildVideoList(targetVideo, videos, targetVideoId)

            } catch (e: Exception) {
                lastError = e.message
                Log.e(TAG, "Attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    val delayMs = calculateBackoff(attempt)
                    Log.w(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                }
            }
        }

        Log.e(TAG, "All $MAX_RETRIES attempts failed. Last error: $lastError")
        _errorMessage.value = lastError ?: "Error de conexión con el servidor"
        return emptyList()
    }

    /**
     * Fetches a single page of videos from the backend (IO dispatcher).
     *
     * Strategy (tiered fallback for maximum reliability):
     *  1. Streaming feed (optimized, server-cached signed URLs) → fastest
     *  2. Standard authenticated feed (signs URLs on-the-fly)
     *  3. Public feed (no auth required, for pre-login browsing)
     */
    private suspend fun fetchPage(page: Int, pageSize: Int): List<VideoData> {
        return withContext(Dispatchers.IO) {
            // Tier 1: Optimized streaming feed with pre-signed, cached URLs
            when (val streamResult = BackendApiService.getStreamingFeed(page = page, limit = pageSize)) {
                is ApiResult.Success -> {
                    val videos = streamResult.data ?: emptyList()
                    if (videos.isNotEmpty()) {
                        Log.d(TAG, "Streaming feed returned ${videos.size} videos (page $page)")
                        return@withContext videos
                    }
                    // Empty result — try standard endpoint
                    Log.w(TAG, "Streaming feed returned empty, falling back to standard")
                }
                is ApiResult.Error -> {
                    Log.w(TAG, "Streaming feed failed: ${streamResult.message}, trying standard endpoint")
                }
            }

            // Tier 2: Standard authenticated feed
            when (val result = BackendApiService.getVideos(page = page, limit = pageSize)) {
                is ApiResult.Success -> {
                    val videos = result.data ?: emptyList()
                    if (videos.isNotEmpty()) return@withContext videos
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "Standard feed error (page $page): ${result.message}")
                }
            }

            // Tier 3: Public feed (no auth)
            Log.d(TAG, "Trying public video feed as last fallback...")
            when (val publicResult = BackendApiService.getPublicStreamingFeed(page = page, limit = pageSize)) {
                is ApiResult.Success -> {
                    Log.i(TAG, "Public feed returned ${publicResult.data?.size ?: 0} videos")
                    publicResult.data ?: emptyList()
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "All feed endpoints failed: ${publicResult.message}")
                    emptyList()
                }
            }
        }
    }

    /**
     * Fetches a specific video by ID. Returns null on failure (non-fatal).
     */
    private suspend fun fetchTargetVideo(videoId: Long): VideoData? {
        return try {
            withContext(Dispatchers.IO) {
                BackendApiService.getVideoById(videoId).getOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching target video $videoId", e)
            null
        }
    }

    /**
     * Builds the final video list with the target video at position 0 if present.
     */
    private fun buildVideoList(
        targetVideo: VideoData?,
        videos: List<VideoData>,
        targetVideoId: Long
    ): List<VideoData> {
        if (targetVideo == null) return videos
        return listOf(targetVideo) + videos.filter { it.id != targetVideoId }
    }

    /**
     * Calculates exponential backoff delay for a given attempt number.
     */
    private fun calculateBackoff(attempt: Int): Long {
        val delay = INITIAL_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, (attempt - 1).toDouble())
        return delay.toLong().coerceAtMost(16_000L) // Cap at 16 seconds
    }

    /**
     * Pre-caches video thumbnails AND the first 2 MB of each video file
     * into ExoPlayer's disk cache for instant playback.
     *
     * Strategy:
     *  - Thumbnails: ALL videos, via Glide disk cache
     *  - Video bytes: First 8 videos, 2 MB each → ~16 MB total (fits in cache)
     *  - Both operations run in parallel for maximum speed
     */
    @androidx.media3.common.util.UnstableApi
    private fun preCacheVideoAssets(videos: List<VideoData>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                com.example.tareamov.util.VideoCacheManager.initialize(context)

                // Parallel: thumbnails + video bytes at the same time
                val thumbnailJob = launch {
                    videos.forEach { video ->
                        video.thumbnailUri?.takeIf { it.isNotEmpty() && it.startsWith("http") }?.let { url ->
                            try {
                                com.bumptech.glide.Glide.with(context)
                                    .downloadOnly()
                                    .load(url)
                                    .submit()
                                    .get()
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                    }
                }

                val videoCacheJob = launch {
                    // Pre-cache the first 8 videos (current + 7 next)
                    val videosToPreCache = videos.take(8)
                    val httpUrls = videosToPreCache.mapNotNull { video ->
                        val url = video.videoUriString ?: video.getBestVideoUri()?.toString()
                        url?.takeIf { it.startsWith("http") }
                    }
                    com.example.tareamov.util.VideoCacheManager.preCacheVideos(context, httpUrls)
                }

                thumbnailJob.join()
                videoCacheJob.join()

                Log.d(TAG, "Pre-cached ${videos.size} thumbnails + ${videos.take(8).size} video headers")
            } catch (e: Exception) {
                Log.w(TAG, "Pre-cache failed", e)
            }
        }
    }
}
