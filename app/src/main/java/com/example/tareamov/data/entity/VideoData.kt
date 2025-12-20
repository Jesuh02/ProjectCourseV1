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
@Entity(
    tableName = "videos",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index("course_id")]
)
data class VideoData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val title: String,
    @androidx.room.ColumnInfo(name = "video_uri_string")
    @SerializedName("video_uri_string") val videoUriString: String? = null,
    @androidx.room.ColumnInfo(name = "local_file_path")
    @SerializedName("local_file_path") val localFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(name = "is_paid")
    val isPaid: Boolean = false,
    @androidx.room.ColumnInfo(name = "thumbnail_uri")
    @SerializedName("thumbnail_uri") val thumbnailUri: String? = null,
    val price: Double? = null,
    @androidx.room.ColumnInfo(name = "course_id")
    @SerializedName("course_id") val courseId: Long? = null, // ID del curso asociado
    @androidx.room.ColumnInfo(name = "remote_id")
    val remoteId: Long? = null,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    @Ignore
    var username: String? = null // Added back as ignored for UI compatibility

    // Transient property that's not stored in the database
    @Ignore
    val videoUri: Uri? = if (videoUriString != null) Uri.parse(videoUriString) else null

    // Secondary constructor for creating from URI
    @Ignore
    constructor(
        description: String,
        title: String,
        videoUri: Uri?,
        isPaid: Boolean = false,
        thumbnailUri: String? = null,
        courseId: Long? = null,
        username: String? = null
    ) : this(
        0,
        description,
        title,
        videoUri?.toString(),
        null,
        System.currentTimeMillis(),
        isPaid,
        thumbnailUri,
        null,
        courseId,
        null,
        System.currentTimeMillis()
    ) {
        this.username = username
    }

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
        // First try to get R2 streaming URL if available
        try {
            val r2Service = com.example.tareamov.service.CloudflareR2Service
            
            // Check if videoUriString is already an R2 URL
            if (r2Service.isR2Url(videoUriString)) {
                return Uri.parse(videoUriString)
            }
            
            // Try to get streaming URL from R2
            val r2Url = r2Service.getVideoStreamUrl(videoUriString)
            if (r2Url != null) {
                return Uri.parse(r2Url)
            }
        } catch (e: Exception) {
            // Fall through to local file check
        }
        
        // Then try the local file path (for locally created videos)
        if (localFilePath != null) {
            val file = File(localFilePath)
            if (file.exists() && file.canRead()) {
                return Uri.fromFile(file)
            }
        }

        // Then try the original URI
        // If the stored string looks like a full HTTP URL (Supabase storage public URL), parse it
        if (!videoUriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(videoUriString)
                // Accept http(s) and file schemes
                if (uri.scheme == "http" || uri.scheme == "https") {
                    // Fix R2 URLs that are missing file extensions
                    var fixedUrl = videoUriString
                    if (fixedUrl.contains(".r2.dev/videos/") && 
                        !fixedUrl.matches(Regex(".*\\.(mp4|mov|avi|mkv|webm|3gp|flv)$"))) {
                        // URL doesn't have a video extension, add .mp4 as default
                        fixedUrl += ".mp4"
                        Log.d("VideoData", "Fixed R2 URL missing extension: $videoUriString -> $fixedUrl")
                    }
                    return Uri.parse(fixedUrl)
                }
                // For file:// scheme, check if file exists
                if (uri.scheme == "file" || uri.scheme == "content") {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists() && file.canRead()) {
                            return uri
                        }
                    }
                    // File doesn't exist locally, try R2 fallback with filename
                    val fileName = videoUriString?.substringAfterLast("/")
                    if (!fileName.isNullOrEmpty()) {
                        // Ensure filename has extension
                        val fileNameWithExt = if (fileName.contains(".")) {
                            fileName
                        } else {
                            "$fileName.mp4"
                        }
                        val r2FallbackUrl = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/videos/$fileNameWithExt"
                        return Uri.parse(r2FallbackUrl)
                    }
                }
            } catch (e: Exception) {
                // fall through to returning the parsed property if available
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