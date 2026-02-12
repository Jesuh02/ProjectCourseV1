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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoHomeViewModel(application: Application) : AndroidViewModel(application) {

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
        BackendApiService.initialize(application.applicationContext)
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
                Log.d("VideoHomeViewModel", "Loading videos from BackendApiService (refresh=$isRefresh, target=$targetVideoId)")

                // If a specific video is requested, try to fetch it first
                var targetVideo: VideoData? = null
                if (targetVideoId != -1L) {
                    try {
                        targetVideo = withContext(Dispatchers.IO) {
                            val result = BackendApiService.getVideoById(targetVideoId)
                            result.getOrNull()
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeViewModel", "Error fetching target video", e)
                    }
                }

                val videos = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getVideos(page = 1, limit = pageSize)
                    when (result) {
                        is ApiResult.Success -> result.data ?: emptyList()
                        is ApiResult.Error -> {
                            Log.e("VideoHomeViewModel", "Backend error: ${result.message}")
                            emptyList()
                        }
                    }
                }

                // Estimate total (backend should return this; for now use list size)
                totalVideos = if (videos.size < pageSize) videos.size else videos.size + pageSize

                val newList = mutableListOf<VideoData>()
                // If we have a target video, add it first
                if (targetVideo != null) {
                    newList.add(targetVideo)
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
                val page = (currentList.size / pageSize) + 1
                val newVideos = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getVideos(page = page + 1, limit = pageSize)
                    when (result) {
                        is ApiResult.Success -> result.data ?: emptyList()
                        is ApiResult.Error -> emptyList()
                    }
                }
                
                if (newVideos.isNotEmpty()) {
                    val combinedList = currentList.toMutableList()
                    combinedList.addAll(newVideos)
                    _videoList.value = combinedList
                    
                    // Update total estimate
                    if (newVideos.size < pageSize) {
                        totalVideos = combinedList.size
                    } else {
                        totalVideos = combinedList.size + pageSize
                    }
                    
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
}
