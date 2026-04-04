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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * ViewModel responsible for loading and managing the video feed.
 *
 * Follows SOLID principles:
 *  - SRP: Only manages video feed state (loading, error, data).
 *  - OCP: Retry strategy can be extended without modifying load logic.
 *  - DIP: Depends on BackendApiService abstraction, not concrete HTTP impl.
 *
 * Performance optimizations:
 *  - Parallel racing: fires streaming, standard and public endpoints simultaneously;
 *    uses the first successful result.
 *  - Aggressive pre-caching: thumbnails + video bytes are pre-fetched in parallel.
 *  - Fast retry: reduces backoff times for transient DNS/network errors.
 */
class VideoHomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoHomeViewModel"
        private const val MAX_RETRIES = 3
        private const val INITIAL_DELAY_MS = 1_000L
        private const val BACKOFF_MULTIPLIER = 1.5
        private const val DEFAULT_PAGE_SIZE = 10
        private const val FEED_TIMEOUT_MS = 8_000L
        private const val CACHE_TTL_MS = 30_000L
        private const val POLL_INTERVAL_MS = 15_000L
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
    var savedPlaybackPositionMs: Int = 0
    var savedPlaybackVideoId: Long = -1L
    var savedPlaybackVideoPath: String? = null

    /** Survives fragment recreation — prevents the network callback from
     *  forcing a full reload when the user returns to this tab. */
    var skipNextNetworkAutoRefresh: Boolean = false

    var totalVideos: Int = 0
        private set

    private var lastTargetVideoId: Long = -1L
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var lastPollTimestamp: Long = 0L
    private var feedFetchedAt: Long = 0L
    private var feedDirty: Boolean = false

    private val pendingDeletions = mutableSetOf<Long>()
    private val pendingAdditions = mutableListOf<VideoData>()
    private var pendingOpsTimestamp: Long = 0L
    private val PENDING_OPS_TTL_MS = 60_000L

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

        // Remember target video ID so refreshes also include it
        if (targetVideoId > 0) {
            lastTargetVideoId = targetVideoId
        }
        // On refresh, reuse the stored target video ID if none provided
        val effectiveTargetId = if (targetVideoId > 0) targetVideoId else lastTargetVideoId

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

            val result = fetchVideosWithRetry(effectiveTargetId, pageSize)

            if (result.isNotEmpty()) {
                val finalList = applyPendingOps(result)
                _videoList.value = finalList
                totalVideos = if (finalList.size < pageSize) finalList.size else finalList.size + pageSize
                feedFetchedAt = System.currentTimeMillis()
                lastPollTimestamp = feedFetchedAt
                feedDirty = false
                preCacheVideoAssets(finalList)
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
                val nextPage = (currentList.size / pageSize) + 2
                val newVideos = fetchPage(nextPage, pageSize)

                if (newVideos.isNotEmpty()) {
                    val combined = applyPendingOps(currentList + newVideos)
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

    fun markFeedDirty() {
        feedDirty = true
    }

    fun isFeedStale(): Boolean =
        feedDirty || System.currentTimeMillis() - feedFetchedAt >= CACHE_TTL_MS

    fun addVideoOptimistic(video: VideoData) {
        val current = _videoList.value.orEmpty().toMutableList()
        current.add(0, video)
        _videoList.value = current
        pendingAdditions.add(video)
        pendingOpsTimestamp = System.currentTimeMillis()
        feedDirty = true
    }

    fun removeVideoOptimistic(videoId: Long) {
        val current = _videoList.value.orEmpty().toMutableList()
        current.removeAll { it.id == videoId }
        _videoList.value = current
        pendingDeletions.add(videoId)
        pendingOpsTimestamp = System.currentTimeMillis()
        feedDirty = true
    }

    private fun applyPendingOps(videos: List<VideoData>): List<VideoData> {
        if (pendingDeletions.isEmpty() && pendingAdditions.isEmpty()) return videos
        if (System.currentTimeMillis() - pendingOpsTimestamp > PENDING_OPS_TTL_MS) {
            pendingDeletions.clear()
            pendingAdditions.clear()
            return videos
        }
        val filtered = videos.filter { it.id !in pendingDeletions }
        val existingIds = filtered.map { it.id }.toSet()
        val newAdditions = pendingAdditions.filter { it.id !in existingIds && it.id !in pendingDeletions }
        return newAdditions + filtered
    }

    fun confirmDeletion(videoId: Long) {
        pendingDeletions.remove(videoId)
    }

    fun confirmAddition(videoId: Long) {
        pendingAdditions.removeAll { it.id == videoId }
    }

    fun updateVideoInPlace(videoId: Long, title: String? = null, description: String? = null, isPaid: Boolean? = null) {
        val current = _videoList.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.id == videoId }
        if (idx < 0) return
        val old = current[idx]
        current[idx] = old.copy(
            title = title ?: old.title,
            description = description ?: old.description,
            isPaid = isPaid ?: old.isPaid
        )
        _videoList.value = current
    }

    fun refreshIfDirty() {
        if (feedDirty) loadVideos(isRefresh = true)
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (lastPollTimestamp > 0L && isDeviceOnline()) {
                    pollForChanges()
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollForChanges() {
        try {
            val result = withContext(Dispatchers.IO) {
                withTimeoutOrNull(FEED_TIMEOUT_MS) {
                    BackendApiService.getVideoChanges(lastPollTimestamp)
                }
            } ?: return

            if (result !is ApiResult.Success || result.data == null) return

            val json = result.data!!
            val serverTimestamp = json.get("serverTimestamp")?.asLong ?: return
            val videosArray = json.getAsJsonArray("videos") ?: return
            if (videosArray.size() == 0) {
                lastPollTimestamp = serverTimestamp
                return
            }

            val gson = Gson()
            val type = object : TypeToken<List<VideoData>>() {}.type
            val changedVideos: List<VideoData> = gson.fromJson(videosArray, type)

            val current = _videoList.value?.toMutableList() ?: return
            var modified = false

            for (changed in changedVideos) {
                val idx = current.indexOfFirst { it.id == changed.id }
                if (idx >= 0) {
                    val old = current[idx]
                    current[idx] = old.copy(
                        title = changed.title,
                        description = changed.description,
                        videoUriString = changed.videoUriString ?: old.videoUriString,
                        thumbnailUri = changed.thumbnailUri ?: old.thumbnailUri,
                        isPaid = changed.isPaid,
                        price = changed.price ?: old.price,
                        updatedAt = changed.updatedAt
                    )
                    modified = true
                } else {
                    current.add(0, changed)
                    modified = true
                }
            }

            if (modified) {
                _videoList.postValue(current)
            }
            lastPollTimestamp = serverTimestamp
        } catch (e: Exception) {
            Log.w(TAG, "Poll for changes failed: ${e.message}")
        }
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

                // Fetch the main video feed using parallel racing
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
     * Strategy: PARALLEL RACING for maximum speed.
     * Fires all 3 endpoints simultaneously and uses the first successful result.
     * This eliminates the serial latency of the old sequential fallback approach.
     *
     *  1. Streaming feed (optimized, server-cached signed URLs) → fastest
     *  2. Standard authenticated feed (signs URLs on-the-fly)
     *  3. Public feed (no auth required, for pre-login browsing)
     */
    private suspend fun fetchPage(page: Int, pageSize: Int): List<VideoData> {
        return withContext(Dispatchers.IO) {
            // Launch all endpoints in parallel — use first successful result
            val streamingDeferred = async {
                try {
                    withTimeoutOrNull(FEED_TIMEOUT_MS) {
                        BackendApiService.getStreamingFeed(page = page, limit = pageSize)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Streaming feed exception: ${e.message}")
                    null
                }
            }

            val standardDeferred = async {
                try {
                    withTimeoutOrNull(FEED_TIMEOUT_MS) {
                        BackendApiService.getVideos(page = page, limit = pageSize)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Standard feed exception: ${e.message}")
                    null
                }
            }

            val publicDeferred = async {
                try {
                    withTimeoutOrNull(FEED_TIMEOUT_MS) {
                        BackendApiService.getPublicStreamingFeed(page = page, limit = pageSize)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Public feed exception: ${e.message}")
                    null
                }
            }

            // Wait for streaming first (fastest expected), then check others
            val streamResult = streamingDeferred.await()
            if (streamResult is ApiResult.Success) {
                val videos = streamResult.data ?: emptyList()
                if (videos.isNotEmpty()) {
                    Log.d(TAG, "Streaming feed returned ${videos.size} videos (page $page)")
                    // Cancel the slower requests
                    standardDeferred.cancel()
                    publicDeferred.cancel()
                    return@withContext videos
                }
            }

            // Streaming failed or empty — check standard
            val standardResult = standardDeferred.await()
            if (standardResult is ApiResult.Success) {
                val videos = standardResult.data ?: emptyList()
                if (videos.isNotEmpty()) {
                    Log.d(TAG, "Standard feed returned ${videos.size} videos (page $page)")
                    publicDeferred.cancel()
                    return@withContext videos
                }
            }

            // Standard failed — check public
            val publicResult = publicDeferred.await()
            if (publicResult is ApiResult.Success) {
                val videos = publicResult.data ?: emptyList()
                Log.d(TAG, "Public feed returned ${videos.size} videos (page $page)")
                return@withContext videos
            }

            Log.e(TAG, "All feed endpoints failed for page $page")
            emptyList()
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
        return delay.toLong().coerceAtMost(8_000L) // Cap at 8 seconds (was 16s)
    }

    /**
     * Pre-caches video thumbnails AND the first 3 MB of each video file
     * into ExoPlayer's disk cache for instant playback.
     *
     * Strategy:
     *  - Thumbnails: ALL videos, via Glide disk cache (parallel)
     *  - Video bytes: First 8 videos, 3 MB each → ~24 MB total
     *  - Both operations run in parallel for maximum speed
     *
     * Principio OCP: la estrategia de pre-cache puede extenderse (ej. adaptive
     * bitrate) sin modificar esta orquestación.
     */
    @androidx.media3.common.util.UnstableApi
    private fun preCacheVideoAssets(videos: List<VideoData>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                com.example.tareamov.util.VideoCacheManager.initialize(context)

                // Step 0: Batch-sign videos that have relative R2 paths (no HTTP URL)
                // This single network call gives us signed URLs for ALL unsigned videos
                // so pre-caching and adapter binding can use them instantly.
                val needSigning = videos.filter { video ->
                    val url = video.videoUriString ?: video.getBestVideoUri()?.toString()
                    url == null || !url.startsWith("http")
                }
                if (needSigning.isNotEmpty()) {
                    try {
                        val ids = needSigning.map { it.id }
                        Log.d(TAG, "Batch-signing ${ids.size} videos with relative R2 paths")
                        val result = BackendApiService.streamingBatchSign(ids)
                        if (result is ApiResult.Success && result.data != null) {
                            val urlMap = result.data!!
                            val updatedList = videos.toMutableList()
                            urlMap.entrySet().forEach { (idStr, urls) ->
                                val videoUrl = urls.asJsonObject?.get("videoUrl")?.asString
                                if (!videoUrl.isNullOrEmpty()) {
                                    val videoId = idStr.toLongOrNull() ?: return@forEach
                                    val idx = updatedList.indexOfFirst { it.id == videoId }
                                    if (idx >= 0) {
                                        updatedList[idx] = updatedList[idx].copy(videoUriString = videoUrl)
                                    }
                                }
                            }
                            // Update LiveData with signed URLs so adapter gets them instantly
                            withContext(Dispatchers.Main) {
                                _videoList.value = updatedList
                            }
                            Log.d(TAG, "Updated ${urlMap.size()} videos with pre-signed URLs")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Batch-sign in preCacheVideoAssets failed: ${e.message}")
                    }
                }

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
                    // Re-read from LiveData to get updated URLs after batch-sign
                    val currentVideos = withContext(Dispatchers.Main) { _videoList.value ?: videos }
                    val videosToPreCache = currentVideos.take(8)
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
