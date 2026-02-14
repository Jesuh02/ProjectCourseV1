package com.example.tareamov.util

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import kotlinx.coroutines.*

/**
 * VideoPreloader — Proactively pre-fetches video bytes for adjacent items
 * so that scrolling feels INSTANT.
 *
 * Responsibilities (SRP):
 *  - Decides WHICH adjacent videos to pre-load based on current scroll position.
 *  - Resolves signed URLs in batch (single network call) via streaming endpoint.
 *  - Delegates actual byte-level caching to [VideoCacheManager].
 *
 * Performance strategy:
 *  1. On page change, batch-sign ±3 adjacent videos in ONE request
 *  2. Immediately pre-fetch first 2 MB of each into ExoPlayer disk cache
 *  3. Cache signed URLs in memory so VideoAdapter gets instant URL resolution
 *  4. Skip videos already cached (no redundant work)
 *
 * Dependency Inversion (DIP):
 *  - Depends on [VideoCacheManager] (cache port) and [BackendApiService] (network port).
 *
 * Usage:
 *   videoPreloader.onPageSelected(position, videoList)
 */
@UnstableApi
class VideoPreloader(private val context: Context) {

    companion object {
        private const val TAG = "VideoPreloader"

        /** How many videos ahead/behind the current position to pre-fetch. */
        private const val PREFETCH_RADIUS = 3

        /** Minimum interval between two pre-fetch sweeps (ms). */
        private const val DEBOUNCE_MS = 150L

        /** Maximum age for a cached signed URL (50 minutes). */
        private const val URL_CACHE_TTL_MS = 50L * 60 * 1000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastPrefetchPosition = -1
    private var prefetchJob: Job? = null

    /** Signed URL cache: videoId → CachedUrl (with TTL) */
    private data class CachedUrl(val url: String, val cachedAt: Long)
    private val signedUrlCache = java.util.concurrent.ConcurrentHashMap<Long, CachedUrl>()

    /**
     * Called every time the ViewPager page changes.
     * Triggers a debounced pre-fetch of adjacent videos.
     */
    fun onPageSelected(currentPosition: Int, videos: List<VideoData>) {
        if (currentPosition == lastPrefetchPosition) return
        lastPrefetchPosition = currentPosition

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            delay(DEBOUNCE_MS) // debounce rapid scrolls
            prefetchAround(currentPosition, videos)
        }
    }

    /**
     * Pre-populates the URL cache with URLs already present in the video list.
     * Call this right after loading the feed to avoid redundant batch-sign calls.
     */
    fun warmCache(videos: List<VideoData>) {
        val now = System.currentTimeMillis()
        for (video in videos) {
            val url = video.videoUriString ?: video.getBestVideoUri()?.toString()
            if (url != null && url.startsWith("http") && !signedUrlCache.containsKey(video.id)) {
                signedUrlCache[video.id] = CachedUrl(url, now)
            }
        }
        Log.d(TAG, "Warmed cache with ${videos.size} video URLs")
    }

    /**
     * Pre-fetches video bytes for positions [current - RADIUS .. current + RADIUS].
     */
    private suspend fun prefetchAround(position: Int, videos: List<VideoData>) {
        if (videos.isEmpty()) return

        val start = (position - PREFETCH_RADIUS).coerceAtLeast(0)
        val end = (position + PREFETCH_RADIUS).coerceAtMost(videos.lastIndex)
        val adjacentVideos = (start..end).map { videos[it] }

        if (adjacentVideos.isEmpty()) return

        Log.d(TAG, "Pre-fetching ${adjacentVideos.size} videos around position $position")

        // 1. Identify videos that need URL resolution
        val now = System.currentTimeMillis()
        val needSigning = adjacentVideos.filter { v ->
            val cached = signedUrlCache[v.id]
            if (cached != null && (now - cached.cachedAt) < URL_CACHE_TTL_MS) return@filter false
            val url = v.videoUriString ?: v.getBestVideoUri()?.toString()
            url == null || !url.startsWith("http")
        }

        // 2. Batch-sign via optimized streaming endpoint (single roundtrip)
        if (needSigning.isNotEmpty()) {
            batchSign(needSigning.map { it.id })
        }

        // 3. Pre-cache video bytes for ALL adjacent videos
        for (video in adjacentVideos) {
            val url = resolveVideoUrl(video)
            if (url != null) {
                VideoCacheManager.preCacheVideo(context, url)
            }
        }
    }

    /**
     * Batch-signs video URLs via the streaming endpoint (with server-side cache).
     */
    private suspend fun batchSign(ids: List<Long>) {
        try {
            // Use streaming batch-sign (backed by server-side URL cache)
            val result = BackendApiService.streamingBatchSign(ids)
            val now = System.currentTimeMillis()
            if (result is ApiResult.Success) {
                val urlMap = result.data
                urlMap?.entrySet()?.forEach { (idStr, urls) ->
                    val videoUrl = urls.asJsonObject?.get("videoUrl")?.asString
                    if (!videoUrl.isNullOrEmpty()) {
                        val videoId = idStr.toLongOrNull() ?: return@forEach
                        signedUrlCache[videoId] = CachedUrl(videoUrl, now)
                    }
                }
                Log.d(TAG, "Batch-signed ${urlMap?.size() ?: 0} URLs via streaming endpoint")
            } else {
                // Fallback to standard batch-sign
                val fallbackResult = BackendApiService.batchSignedUrls(ids)
                if (fallbackResult is ApiResult.Success) {
                    val urlMap = fallbackResult.data
                    urlMap?.entrySet()?.forEach { (idStr, urls) ->
                        val videoUrl = urls.asJsonObject?.get("videoUrl")?.asString
                        if (!videoUrl.isNullOrEmpty()) {
                            val videoId = idStr.toLongOrNull() ?: return@forEach
                            signedUrlCache[videoId] = CachedUrl(videoUrl, now)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Batch sign failed: ${e.message}")
        }
    }

    /**
     * Resolves the best playable URL for a video:
     *  1. In-memory signed URL cache (instant)
     *  2. HTTP URL already in the VideoData model
     *  3. CloudflareR2Service fallback (individual signing, last resort)
     */
    private suspend fun resolveVideoUrl(video: VideoData): String? {
        // 1. Check signed URL cache
        signedUrlCache[video.id]?.let { return it.url }

        // 2. Check model URL (already signed by backend streaming endpoint)
        val modelUrl = video.videoUriString ?: video.getBestVideoUri()?.toString()
        if (modelUrl != null && modelUrl.startsWith("http")) {
            signedUrlCache[video.id] = CachedUrl(modelUrl, System.currentTimeMillis())
            return modelUrl
        }

        // 3. Fallback: individual R2 signing
        if (!modelUrl.isNullOrEmpty()) {
            return try {
                val signed = com.example.tareamov.service.StorageHelper.getVideoStreamUrl(context, modelUrl)
                if (signed != null) {
                    signedUrlCache[video.id] = CachedUrl(signed, System.currentTimeMillis())
                }
                signed
            } catch (_: Exception) { null }
        }
        return null
    }

    /**
     * Retrieve a pre-resolved signed URL for a video ID (if available and not expired).
     * The [VideoAdapter] can use this to skip the per-item URL resolution.
     */
    fun getPreSignedUrl(videoId: Long): String? {
        val cached = signedUrlCache[videoId] ?: return null
        if (System.currentTimeMillis() - cached.cachedAt > URL_CACHE_TTL_MS) {
            signedUrlCache.remove(videoId)
            return null
        }
        return cached.url
    }

    /**
     * Cancel all in-flight pre-fetch work (e.g., when the fragment is destroyed).
     */
    fun cancel() {
        prefetchJob?.cancel()
        scope.cancel()
    }
}
