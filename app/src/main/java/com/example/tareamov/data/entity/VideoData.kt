package com.example.tareamov.data.entity

import android.net.Uri
import android.util.Log
import androidx.room.ColumnInfo
import com.google.gson.annotations.SerializedName
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.io.File

/**
 * Data class representing video information for the VideoHomeFragment
 *
 * @property id Unique identifier for the video
 * @property username The username of the video creator
 * @property description The description of the video content
 * @property title The title of the video
 * @property videoUriString The URI of the video file as a string
 * @property localFilePath The local file path for persistent storage
 * @property timestamp When the video was uploaded
 * @property isPaid Whether the course is paid or free
 */
@Entity(tableName = "videos")
data class VideoData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @SerializedName(value = "username", alternate = ["creator_username", "user"]) val username: String = "", // This is the creator's username (may be resolved from remote_id)
    val description: String? = null,
    val title: String = "",
    @SerializedName(value = "video_uri_string", alternate = ["videoUriString"]) val videoUriString: String? = null,
    @SerializedName(value = "local_file_path", alternate = ["localFilePath"]) val localFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("is_paid") val isPaid: Boolean = false,
    @SerializedName(value = "thumbnail_uri", alternate = ["thumbnailUri"]) val thumbnailUri: String? = null,
    @SerializedName("price") val price: Double? = null,
    @SerializedName(value = "course_id", alternate = ["courseId"]) val courseId: Long? = null, // ID del curso asociado
    @SerializedName(value = "remote_id", alternate = ["remoteId"]) val remoteId: Long? = null // ID del usuario creador (según requerimiento)
) {
    // Transient property that's not stored in the database
    @Ignore
    val videoUri: Uri? = if (videoUriString != null) Uri.parse(videoUriString) else null

    // Secondary constructor for creating from URI
    constructor(
        username: String,
        description: String? = null,
        title: String,
        videoUri: Uri?,
        isPaid: Boolean = false,
        thumbnailUri: String? = null,
        courseId: Long? = null,
        remoteId: Long? = null
    ) : this(
        0,
        username,
        description,
        title,
        videoUri?.toString(),
        null,
        System.currentTimeMillis(),
        isPaid,
        thumbnailUri,
        null,
        courseId,
        remoteId
    )

    // Check if the video file exists
    fun videoFileExists(): Boolean {
        if (localFilePath != null) {
            val file = File(localFilePath)
            return file.exists() && file.canRead()
        }

        if (videoUriString != null && videoUriString.startsWith("file://")) {
            val path = videoUriString.replace("file://", "")
            val file = File(path)
            return file.exists() && file.canRead()
        }

        return false
    }

    // Get the best available URI for playback
    fun getBestVideoUri(): Uri? {
        // Optimization: Avoid blocking network calls here.
        // The VideoPlayerActivity will handle fetching the streaming URL asynchronously.
        
        // 1. If videoUriString is a remote HTTP/HTTPS URL, use it directly (highest priority for cloud videos)
        if (!videoUriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(videoUriString)
                if (uri.scheme == "http" || uri.scheme == "https") {
                    return uri
                }
            } catch (_: Exception) { }
        }

        // 2. Try the local file path (for locally created videos on same device)
        if (localFilePath != null) {
            val file = File(localFilePath)
            if (file.exists() && file.canRead()) {
                return Uri.fromFile(file)
            }
        }

        // 3. Try videoUriString as local file if it's a file:// or content:// URI
        if (!videoUriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(videoUriString)
                if (uri.scheme == "file" || uri.scheme == "content") {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists() && file.canRead()) {
                            return uri
                        }
                    }
                    // File doesn't exist locally — try R2 fallback with filename
                    val fileName = videoUriString?.substringAfterLast("/")
                    if (!fileName.isNullOrEmpty()) {
                        val r2FallbackUrl = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/videos/$fileName"
                        Log.d("VideoData", "File not found locally, trying R2 fallback: $r2FallbackUrl")
                        return Uri.parse(r2FallbackUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoData", "Error parsing videoUriString: ${e.message}")
            }
        }

        return videoUri
    }
}

/**
 * Type converters for Room database
 */
class VideoDataConverters {
    @TypeConverter
    fun fromUri(uri: Uri?): String? {
        return uri?.toString()
    }

    @TypeConverter
    fun toUri(uriString: String?): Uri? {
        return if (uriString == null) null else Uri.parse(uriString)
    }
}