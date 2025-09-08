package com.example.tareamov.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility class for generating and managing video thumbnails
 */
class ThumbnailManager(private val context: Context) {

    companion object {
        private const val TAG = "ThumbnailManager"
        private const val THUMBNAIL_DIRECTORY = "thumbnails"
        private const val THUMBNAIL_QUALITY = 85
        private const val THUMBNAIL_WIDTH = 480
        private const val THUMBNAIL_HEIGHT = 270
        
        // Multiple time positions to try for thumbnail generation (in microseconds)
        private val THUMBNAIL_TIME_POSITIONS = longArrayOf(
            1000000L,    // 1 second
            2000000L,    // 2 seconds
            5000000L,    // 5 seconds
            10000000L,   // 10 seconds
            0L           // Beginning of video
        )
    }

    /**
     * Generate thumbnail from video and save it to internal storage
     */
    suspend fun generateAndSaveThumbnail(videoUri: String, videoId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val bitmap = extractThumbnailFromVideo(videoUri) ?: return@withContext null
            return@withContext saveThumbnailToInternalStorage(bitmap, videoId)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for video ID: $videoId", e)
            return@withContext null
        }
    }

    /**
     * Extract thumbnail bitmap from video at multiple time positions
     */
    private fun extractThumbnailFromVideo(videoUri: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        
        try {
            // Set data source
            when {
                videoUri.startsWith("file://") -> {
                    val path = videoUri.replace("file://", "")
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) {
                        Log.e(TAG, "Video file not accessible: $path")
                        return null
                    }
                    retriever.setDataSource(path)
                }
                videoUri.startsWith("content://") -> {
                    retriever.setDataSource(context, Uri.parse(videoUri))
                }
                else -> {
                    // Try as file path directly
                    val file = File(videoUri)
                    if (file.exists() && file.canRead()) {
                        retriever.setDataSource(videoUri)
                    } else {
                        Log.e(TAG, "Invalid video URI format: $videoUri")
                        return null
                    }
                }
            }

            // Get video duration to validate time positions
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationString?.toLongOrNull() ?: 0L
            val durationMicros = duration * 1000 // Convert to microseconds

            Log.d(TAG, "Video duration: ${duration}ms (${durationMicros}μs)")

            // Try different time positions to get a good thumbnail
            for (timePosition in THUMBNAIL_TIME_POSITIONS) {
                try {
                    // Skip time positions that exceed video duration
                    if (timePosition > durationMicros && timePosition != 0L) {
                        continue
                    }
                    
                    val actualTime = if (timePosition > durationMicros) durationMicros / 2 else timePosition
                    Log.d(TAG, "Trying to extract frame at time: ${actualTime}μs")
                    
                    val frame = retriever.getFrameAtTime(
                        actualTime, 
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    
                    if (frame != null) {
                        Log.d(TAG, "Successfully extracted frame at time: ${actualTime}μs")
                        return scaleBitmapToThumbnailSize(frame)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract frame at time: $timePosition, trying next position", e)
                    continue
                }
            }
            
            Log.e(TAG, "Failed to extract thumbnail from any time position")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting thumbnail from video: $videoUri", e)
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }

    /**
     * Scale bitmap to thumbnail size
     */
    private fun scaleBitmapToThumbnailSize(originalBitmap: Bitmap): Bitmap {
        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(
                originalBitmap, 
                THUMBNAIL_WIDTH, 
                THUMBNAIL_HEIGHT, 
                true
            )
            
            // Recycle original if it's different from scaled
            if (scaledBitmap != originalBitmap) {
                originalBitmap.recycle()
            }
            
            scaledBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error scaling bitmap", e)
            originalBitmap
        }
    }

    /**
     * Save thumbnail bitmap to internal storage
     */
    private fun saveThumbnailToInternalStorage(bitmap: Bitmap, videoId: Long): String? {
        return try {
            val thumbnailDir = File(context.filesDir, THUMBNAIL_DIRECTORY)
            if (!thumbnailDir.exists()) {
                thumbnailDir.mkdirs()
            }

            val thumbnailFile = File(thumbnailDir, "thumbnail_$videoId.jpg")
            val outputStream = FileOutputStream(thumbnailFile)
            
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, outputStream)
            outputStream.flush()
            outputStream.close()
            
            if (success) {
                Log.d(TAG, "Thumbnail saved successfully: ${thumbnailFile.absolutePath}")
                return thumbnailFile.absolutePath
            } else {
                Log.e(TAG, "Failed to compress and save thumbnail")
                return null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error saving thumbnail to internal storage", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Get thumbnail file path for a video ID
     */
    fun getThumbnailPath(videoId: Long): String {
        val thumbnailDir = File(context.filesDir, THUMBNAIL_DIRECTORY)
        return File(thumbnailDir, "thumbnail_$videoId.jpg").absolutePath
    }

    /**
     * Check if thumbnail exists for a video ID
     */
    fun thumbnailExists(videoId: Long): Boolean {
        val thumbnailPath = getThumbnailPath(videoId)
        val file = File(thumbnailPath)
        return file.exists() && file.canRead() && file.length() > 0
    }

    /**
     * Delete thumbnail for a video ID
     */
    fun deleteThumbnail(videoId: Long): Boolean {
        return try {
            val thumbnailPath = getThumbnailPath(videoId)
            val file = File(thumbnailPath)
            if (file.exists()) {
                file.delete()
            } else {
                true // Consider it deleted if it doesn't exist
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting thumbnail for video ID: $videoId", e)
            false
        }
    }

    /**
     * Generate thumbnail if it doesn't exist
     */
    suspend fun ensureThumbnailExists(videoUri: String, videoId: Long): String? {
        return if (thumbnailExists(videoId)) {
            getThumbnailPath(videoId)
        } else {
            generateAndSaveThumbnail(videoUri, videoId)
        }
    }
}
