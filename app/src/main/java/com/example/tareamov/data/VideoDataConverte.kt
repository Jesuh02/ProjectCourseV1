package com.example.tareamov.data

import android.net.Uri
import androidx.room.TypeConverter

/**
 * Type converters for Room database to handle Uri objects
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