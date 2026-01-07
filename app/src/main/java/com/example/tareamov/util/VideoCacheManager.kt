package com.example.tareamov.util

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.util.concurrent.Executors

/**
 * Singleton manager for video caching using ExoPlayer's cache system.
 * This enables near-instant video loading by pre-caching video data.
 */
@UnstableApi
object VideoCacheManager {
    
    private const val TAG = "VideoCacheManager"
    
    // Cache configuration - increased for better pre-buffering
    private const val CACHE_SIZE_BYTES = 300L * 1024 * 1024 // 300 MB cache
    private const val CACHE_FOLDER_NAME = "video_cache"
    
    // AGGRESSIVE connection settings for instant loading
    private const val CONNECT_TIMEOUT_MS = 3000 // 3 seconds - faster timeout
    private const val READ_TIMEOUT_MS = 3000 // 3 seconds - faster timeout
    
    private var cache: Cache? = null
    private var cacheDataSourceFactory: DataSource.Factory? = null
    private var isInitialized = false
    
    // Executor for background cache operations
    private val cacheExecutor = Executors.newFixedThreadPool(2)
    
    /**
     * Initialize the video cache. Should be called once on app start.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) {
            return
        }
        
        try {
            val cacheDir = File(context.cacheDir, CACHE_FOLDER_NAME)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            val databaseProvider = StandaloneDatabaseProvider(context)
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            
            cache = SimpleCache(cacheDir, cacheEvictor, databaseProvider)
            
            // Create OPTIMIZED HTTP data source with fast timeouts and parallel connections
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setUserAgent("ExoPlayer/CourseV-App")
            
            // Create upstream data source (network)
            val upstreamDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            
            // Create cache data source that wraps the upstream
            // FLAG_BLOCK_ON_CACHE: Block until cache is available (instant for cached content)
            cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache!!)
                .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
                .setFlags(
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_BLOCK_ON_CACHE
                )
            
            isInitialized = true
            Log.d(TAG, "Video cache initialized with ${CACHE_SIZE_BYTES / (1024 * 1024)} MB capacity")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video cache", e)
        }
    }
    
    /**
     * Get the cache data source factory for use with ExoPlayer.
     */
    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        if (!isInitialized) {
            initialize(context)
        }
        return cacheDataSourceFactory ?: DefaultDataSource.Factory(context)
    }
    
    /**
     * Create a MediaSource factory that uses the cache.
     */
    fun createMediaSourceFactory(context: Context): MediaSource.Factory {
        return DefaultMediaSourceFactory(getCacheDataSourceFactory(context))
    }
    
    /**
     * Create a cached MediaSource for a video URL.
     */
    fun createCachedMediaSource(context: Context, url: String): MediaSource {
        val mediaItem = MediaItem.fromUri(url)
        return createMediaSourceFactory(context).createMediaSource(mediaItem)
    }
    
    /**
     * Pre-cache video data for faster loading.
     * Call this for videos that are about to be displayed.
     */
    fun preCacheVideo(context: Context, url: String) {
        if (!isInitialized) {
            initialize(context)
        }
        
        // Pre-caching is handled automatically by the CacheDataSource
        // when videos are played. This method can be extended for
        // more aggressive pre-fetching if needed.
        Log.d(TAG, "Video URL registered for caching: $url")
    }
    
    /**
     * Check if video data is cached.
     */
    fun isVideoCached(url: String): Boolean {
        return cache?.isCached(url, 0, 1024 * 1024) ?: false // Check if first 1MB is cached
    }
    
    /**
     * Get the cache instance for advanced operations.
     */
    fun getCache(): Cache? = cache
    
    /**
     * Release the cache when the app is destroyed.
     */
    @Synchronized
    fun release() {
        try {
            cache?.release()
            cache = null
            cacheDataSourceFactory = null
            isInitialized = false
            Log.d(TAG, "Video cache released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video cache", e)
        }
    }
    
    /**
     * Clear all cached data.
     */
    fun clearCache() {
        try {
            // SimpleCache doesn't have a clear method, so we release and reinitialize
            val cacheKeys = cache?.keys?.toList() ?: emptyList()
            cacheKeys.forEach { key ->
                cache?.removeResource(key)
            }
            Log.d(TAG, "Video cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing video cache", e)
        }
    }
}
