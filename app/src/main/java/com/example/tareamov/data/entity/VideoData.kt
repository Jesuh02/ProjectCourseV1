package com.example.tareamov.data.entity

import android.net.Uri
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
    @SerializedName("username") val username: String, // This is the creator's username
    val description: String,
    val title: String,
    @SerializedName("video_uri_string") val videoUriString: String? = null,
    @SerializedName("local_file_path") val localFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isPaid: Boolean = false,
    @SerializedName("thumbnail_uri") val thumbnailUri: String? = null,
    val price: Double? = null,
    @SerializedName("course_id") val courseId: Long? = null // ID del curso asociado
) {
    // Transient property that's not stored in the database
    @Ignore
    val videoUri: Uri? = if (videoUriString != null) Uri.parse(videoUriString) else null

    // Secondary constructor for creating from URI
    constructor(
        username: String,
        description: String,
        title: String,
        videoUri: Uri?,
        isPaid: Boolean = false,
        thumbnailUri: String? = null,
        courseId: Long? = null
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
        courseId
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
        // First try the local file path
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
                if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "file" || uri.scheme == "content") {
                    return uri
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