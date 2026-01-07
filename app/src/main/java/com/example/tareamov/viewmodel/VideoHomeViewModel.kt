package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.sync.SyncRepository
import com.example.tareamov.service.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoHomeViewModel(application: Application) : AndroidViewModel(application) {

    private val syncRepository: SyncRepository
    
    // Backing property for video list
    private val _videoList = MutableLiveData<List<VideoData>>(emptyList())
    val videoList: LiveData<List<VideoData>> = _videoList

    // State for loading
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading
    
    // State for error (no connection or load failure)
    private val _hasError = MutableLiveData<Boolean>(false)
    val hasError: LiveData<Boolean> = _hasError

    // State for current video index
    var currentVideoIndex: Int = 0

    // Store total videos count for pagination
    var totalVideos: Int = 0
        private set

    init {
        val database = AppDatabase.getDatabase(application)
        syncRepository = SyncRepository(
            usuarioDao = database.usuarioDao(),
            personaDao = database.personaDao(),
            topicDao = database.topicDao(),
            contentItemDao = database.contentItemDao(),
            taskDao = database.taskDao(),
            subscriptionDao = database.subscriptionDao(),
            taskSubmissionDao = database.taskSubmissionDao(),
            videoDao = database.videoDao(),
            courseDao = database.courseDao(),
            rolDao = database.rolDao(),
            recursoDao = database.recursoDao(),
            rolRecursoDao = database.rolRecursoDao(),
            chatMessageDao = database.chatMessageDao(),
            fileContextDao = database.fileContextDao(),
            progresoEstudianteDao = database.progresoEstudianteDao(),
            likeDao = database.likeDao(),
            videoCommentDao = database.videoCommentDao()
        )
        
        // Initialize context for sync repository
        try {
            syncRepository.initWithContext(application)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadVideos(
        targetVideoId: Long = -1L,
        pageSize: Int = 10,
        isRefresh: Boolean = false
    ) {
        if (_isLoading.value == true && !isRefresh) {
            return
        }

        // If we already have videos and it's not a refresh or specific target load, skip
        if (!isRefresh && _videoList.value?.isNotEmpty() == true && targetVideoId == -1L) {
            return
        }

        _isLoading.value = true
        _hasError.value = false // Reset error state on new load
        viewModelScope.launch {
            try {
                Log.d("VideoHomeViewModel", "Loading videos (refresh=$isRefresh, target=$targetVideoId)")
                
                // OPTIMIZATION: Load from local cache FIRST for instant display
                // BUT skip cache when refreshing to ensure fresh data from network
                // This prevents showing stale data with missing usernames, etc.
                if (!isRefresh) {
                    val localCacheJob = viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val database = AppDatabase.getDatabase(getApplication())
                            val cachedVideos = database.videoDao().getAllVideos()
                            if (cachedVideos.isNotEmpty() && _videoList.value.isNullOrEmpty()) {
                                withContext(Dispatchers.Main) {
                                    _videoList.value = cachedVideos.take(pageSize)
                                    Log.d("VideoHomeViewModel", "Loaded ${cachedVideos.size} videos from local cache (instant)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("VideoHomeViewModel", "Local cache load failed", e)
                        }
                    }
                } else {
                    Log.d("VideoHomeViewModel", "Skipping local cache - forcing network refresh")
                }

                // If a specific video is requested, try to fetch it first
                var targetVideo: VideoData? = null
                if (targetVideoId != -1L) {
                    try {
                        targetVideo = withContext(Dispatchers.IO) {
                            SupabaseClient.fetchVideoById(targetVideoId)
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeViewModel", "Error fetching target video", e)
                    }
                }

                val result = withContext(Dispatchers.IO) {
                    syncRepository.fetchVideosPaginated(
                        limit = pageSize,
                        offset = 0
                    )
                }
                val videos = result.first
                val total = result.second

                totalVideos = total

                val newList = mutableListOf<VideoData>()
                // If we have a target video, add it first
                if (targetVideo != null) {
                    newList.add(targetVideo)
                    // Add other videos, excluding the target if it's already in the list
                    val others = videos.filter { it.id != targetVideoId }
                    newList.addAll(others)
                } else {
                    newList.addAll(videos)
                }

                _videoList.value = newList
                
                // OPTIMIZATION: Pre-cache thumbnails and video metadata for instant display
                preCacheVideoAssets(newList)
                
                // Set error if no videos loaded
                if (newList.isEmpty()) {
                    _hasError.value = true
                }
                
            } catch (e: Exception) {
                Log.e("VideoHomeViewModel", "Error loading videos", e)
                _hasError.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMoreVideos(pageSize: Int = 10) {
        if (_isLoading.value == true) return
        
        val currentList = _videoList.value ?: emptyList()
        if (currentList.size >= totalVideos) return

        _isLoading.value = true
        viewModelScope.launch {
            try {
                val offset = currentList.size
                val result = withContext(Dispatchers.IO) {
                    syncRepository.fetchVideosPaginated(
                        limit = pageSize,
                        offset = offset
                    )
                }
                val newVideos = result.first
                // Update total just in case
                totalVideos = result.second
                
                if (newVideos.isNotEmpty()) {
                    val combinedList = currentList.toMutableList()
                    combinedList.addAll(newVideos)
                    _videoList.value = combinedList
                    
                    // Pre-cache the new videos too
                    preCacheVideoAssets(newVideos)
                }
            } catch (e: Exception) {
                Log.e("VideoHomeViewModel", "Error loading more videos", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Pre-cache thumbnails and video metadata for instant display.
     * This runs in the background and improves perceived loading speed.
     */
    private fun preCacheVideoAssets(videos: List<VideoData>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<android.app.Application>()
                
                // Pre-cache thumbnails using Glide
                videos.forEach { video ->
                    video.thumbnailUri?.let { thumbnailUrl ->
                        if (thumbnailUrl.isNotEmpty()) {
                            try {
                                // Download thumbnail to disk cache
                                com.bumptech.glide.Glide.with(context)
                                    .downloadOnly()
                                    .load(thumbnailUrl)
                                    .submit()
                                    .get() // Block to ensure it's cached
                            } catch (e: Exception) {
                                // Ignore individual thumbnail failures
                            }
                        }
                    }
                }
                
                Log.d("VideoHomeViewModel", "Pre-cached ${videos.size} video thumbnails")
            } catch (e: Exception) {
                Log.w("VideoHomeViewModel", "Pre-cache failed", e)
            }
        }
    }
    
    // Helper accessors for repository methods if needed by Fragment
    fun getSyncRepository(): SyncRepository {
        return syncRepository
    }
}
