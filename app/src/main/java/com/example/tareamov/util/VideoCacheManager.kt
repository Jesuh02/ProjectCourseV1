package com.example.tareamov.util

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*

/**
 * Singleton manager for video caching using ExoPlayer's cache system.
 *
 * Responsibilities (SRP):
 *  - Manages a single shared SimpleCache instance (300 MB LRU).
 *  - Provides CacheDataSource.Factory for ExoPlayer instances.
 *  - Pre-fetches video bytes into cache for INSTANT playback of adjacent videos.
 *
 * Open/Closed: the pre-fetch strategy can be extended (e.g., adaptive bitrate)
 * without modifying the core cache setup.
 */
@UnstableApi
object VideoCacheManager {

    private const val TAG = "VideoCacheManager"

    // ─── Configuration ────────────────────────────────────────
    private const val CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB LRU (generous for instant loading)
    private const val CACHE_FOLDER_NAME = "video_cache"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000

    /** How many bytes to pre-fetch per video (first 3 MB → ~3-6 s of 720p).
     *  This ensures ExoPlayer can start playback INSTANTLY from cache. */
    private const val PRE_FETCH_BYTES = 3L * 1024 * 1024

    // ─── State ────────────────────────────────────────────────
    private var cache: Cache? = null
    private var cacheDataSourceFactory: DataSource.Factory? = null
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var isInitialized = false
    private var appContextRef: java.lang.ref.WeakReference<Context>? = null

    /** Dedicated thread pool for background pre-fetch I/O */
    private val preFetchExecutor = Executors.newFixedThreadPool(3)
    private val preFetchScope = CoroutineScope(preFetchExecutor.asCoroutineDispatcher() + SupervisorJob())

    /** Tracks URLs currently being pre-fetched to avoid duplicate work */
    private val activePrefetches = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ─── Initialization ───────────────────────────────────────

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            appContextRef = java.lang.ref.WeakReference(context.applicationContext)
            val cacheDir = File(context.cacheDir, CACHE_FOLDER_NAME).also { it.mkdirs() }
            val databaseProvider = StandaloneDatabaseProvider(context)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            cache = SimpleCache(cacheDir, evictor, databaseProvider)
            buildFactories()
            isInitialized = true
            Log.d(TAG, "Video cache initialized (${CACHE_SIZE_BYTES / (1024 * 1024)} MB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video cache", e)
        }
    }

    private fun buildFactories() {
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setUserAgent("ExoPlayer/CourseV-App")

        val ctx = getContextOrNull() ?: return
        val upstream = DefaultDataSource.Factory(ctx, httpDataSourceFactory!!)
        val c = cache ?: return

        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    private fun getContextOrNull(): Context? = appContextRef?.get()

    // ─── Public API: DataSource factory ───────────────────────

    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        if (appContextRef == null) appContextRef = java.lang.ref.WeakReference(context.applicationContext)
        if (!isInitialized) initialize(context)
        return cacheDataSourceFactory ?: DefaultDataSource.Factory(context)
    }

    /**
     * Create tolerant MediaSource factory backed by disk cache.
     */
    fun createMediaSourceFactory(context: Context): MediaSource.Factory {
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        return DefaultMediaSourceFactory(getCacheDataSourceFactory(context), extractorsFactory)
    }

    /**
     * Create a cached MediaSource for a URL.
     * Uses a stable cache key so that different presigned URLs for the
     * same R2 object share the same cache entry.
     */
    fun createCachedMediaSource(context: Context, url: String): MediaSource {
        val builder = MediaItem.Builder().setUri(url)

        // Force MP4 mime for extensionless R2 URLs
        val isR2 = url.contains("r2.dev") || url.contains("r2.cloudflarestorage.com")
        val lower = url.lowercase()
        val hasExt = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mov")
        if (isR2 && !hasExt) {
            builder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
        }
        extractStableCacheKey(url)?.let { key ->
            builder.setCustomCacheKey(key)
            Log.d(TAG, "Stable cache key: $key")
        }
        return createMediaSourceFactory(context).createMediaSource(builder.build())
    }

    // ─── Pre-fetch API ────────────────────────────────────────

    /**
     * Pre-fetch the first [PRE_FETCH_BYTES] of a video URL into the disk cache.
     * Called for adjacent (N±1, N±2) videos so they are ready for instant playback.
     *
     * This is a *fire-and-forget* operation — it runs on a background thread pool
     * and silently catches errors (network failures, cancelled jobs, etc.).
     *
     * @param url  The (presigned) video URL.
     */
    fun preCacheVideo(context: Context, url: String) {
        if (!isInitialized) initialize(context)
        val c = cache ?: return
        val stableKey = extractStableCacheKey(url)

        // Skip if already cached
        if (stableKey != null && c.isCached(stableKey, 0, PRE_FETCH_BYTES)) {
            Log.d(TAG, "Already cached (${PRE_FETCH_BYTES / 1024}KB): $stableKey")
            return
        }
        // Skip if already in flight
        val dedupeKey = stableKey ?: url
        if (activePrefetches.putIfAbsent(dedupeKey, true) != null) return

        preFetchScope.launch {
            try {
                val upstream = httpDataSourceFactory?.createDataSource() ?: return@launch

                val cacheDataSource = CacheDataSource(
                    c, upstream,
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                )

                val dataSpec = DataSpec.Builder()
                    .setUri(url)
                    .setLength(PRE_FETCH_BYTES)
                    .apply { if (stableKey != null) setKey(stableKey) }
                    .build()

                val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
                writer.cache()

                Log.d(TAG, "Pre-fetched ${PRE_FETCH_BYTES / 1024}KB for: $dedupeKey")
            } catch (e: Exception) {
                Log.w(TAG, "Pre-fetch failed for $dedupeKey: ${e.message}")
            } finally {
                activePrefetches.remove(dedupeKey)
            }
        }
    }

    /**
     * Pre-fetch multiple video URLs concurrently.
     * Ideal for batch pre-loading after receiving signed URLs from the backend.
     */
    fun preCacheVideos(context: Context, urls: List<String>) {
        urls.forEach { url -> preCacheVideo(context, url) }
    }

    // ─── Cache queries ────────────────────────────────────────

    fun isVideoCached(url: String): Boolean {
        val key = extractStableCacheKey(url) ?: url
        return cache?.isCached(key, 0, PRE_FETCH_BYTES) ?: false
    }

    fun getCache(): Cache? = cache

    @Synchronized
    fun release() {
        try {
            preFetchScope.cancel()
            cache?.release()
            cache = null
            cacheDataSourceFactory = null
            httpDataSourceFactory = null
            isInitialized = false
            Log.d(TAG, "Video cache released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video cache", e)
        }
    }

    fun clearCache() {
        try {
            cache?.keys?.toList()?.forEach { cache?.removeResource(it) }
            Log.d(TAG, "Video cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing video cache", e)
        }
    }

    // ─── Internals ────────────────────────────────────────────

    /**
     * Extract stable cache key from R2 URLs (signed or public) so that
     * different presigned URLs for the same object share the same cache entry.
     *
     * This is critical for cache hit rates: without stable keys, each new
     * signed URL would be treated as a different resource, wasting disk space
     * and forcing re-downloads.
     */
    private fun extractStableCacheKey(url: String): String? {
        val isR2 = url.contains("r2.dev") || url.contains("r2.cloudflarestorage.com")
        if (!isR2) return null
        return try {
            android.net.Uri.parse(url).path?.trimStart('/')
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting cache key", e)
            null
        }
    }
}
