package com.example.tareamov.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Utility class to manage URI permissions
 */
class UriPermissionManager(private val context: Context) {

    /**
     * Take persistable permission for a URI
     * @return true if permission was successfully taken
     */
    fun takePersistablePermission(uri: Uri): Boolean {
        return try {
            // Skip dummy or unsupported URIs
            if (uri.toString().contains("dummy") || uri.toString().contains("unsupported")) {
                Log.w("UriPermissionManager", "Cannot take persistable permission for dummy or unsupported URI: $uri")
                return false
            }
            if (uri.scheme == "content") {
                // Skip persistable permission for known unsupported authorities (e.g., MIUI Gallery)
                if (uri.authority?.contains("miui.gallery.open") == true || uri.authority?.contains("miui.gallery.provider") == true) {
                    Log.w("UriPermissionManager", "Persistable permission not supported for MIUI Gallery URIs: $uri")
                    return false
                }
                // Check if we already have permission
                if (hasPermissionForUri(uri)) {
                    Log.d("UriPermissionManager", "Already have permission for URI: $uri")
                    return true
                }

                // Try to take permission with both read and write flags
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                try {
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    Log.d("UriPermissionManager", "Successfully took persistable permission for URI: $uri")
                    return true
                } catch (e: SecurityException) {
                    // Try with just read permission if both failed
                    val readFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, readFlags)
                    Log.d("UriPermissionManager", "Took read-only persistable permission for URI: $uri")
                    return true
                }
            } else {
                Log.d("UriPermissionManager", "URI scheme is not content, no need for persistable permission: $uri")
                true // No need for permission for non-content URIs
            }
        } catch (e: Exception) {
            Log.e("UriPermissionManager", "Failed to take persistable permission for URI: $uri", e)
            false
        }
    }

    /**
     * Check if we already have persistable permission for a URI
     */
    fun hasPermissionForUri(uri: Uri): Boolean {
        // Skip dummy or unsupported URIs
        if (uri.toString().contains("dummy") || uri.toString().contains("unsupported")) {
            Log.w("UriPermissionManager", "Cannot check permission for dummy or unsupported URI: $uri")
            return false
        }
        if (uri.scheme != "content") {
            return true // No need for permission for non-content URIs
        }

        val persistedUriPermissions = context.contentResolver.persistedUriPermissions
        return persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    /**
     * Release persistable permission for a URI
     */
    fun releasePermission(uri: Uri) {
        try {
            if (uri.scheme == "content") {
                context.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Log.d("UriPermissionManager", "Released permission for URI: $uri")
            }
        } catch (e: Exception) {
            Log.e("UriPermissionManager", "Error releasing permission for URI: $uri", e)
        }
    }

    /**
     * Convert a possibly temporary URI to a persistable one
     * This is useful for URIs from ACTION_PICK which might not be persistable
     */
    fun convertToDocumentUri(uri: Uri): Uri? {
        // Skip dummy or unsupported URIs
        if (uri.toString().contains("dummy") || uri.toString().contains("unsupported")) {
            Log.w("UriPermissionManager", "Cannot convert dummy or unsupported URI: $uri")
            return uri
        }
        // If it's already a document URI, return it
        if (uri.toString().startsWith("content://com.android.providers.media.documents") ||
            uri.toString().startsWith("content://com.android.externalstorage.documents")) {
            return uri
        }

        // For gallery URIs, we need to use the MediaStore to get the actual file path
        // and then create a new URI from it
        try {
            val projection = arrayOf("_data")
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow("_data")
                    val filePath = cursor.getString(columnIndex)
                    if (filePath != null) {
                        // Create a content URI from the file path
                        return Uri.parse("file://$filePath")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UriPermissionManager", "Error converting URI: $uri", e)
        }

        return uri // Return the original URI if conversion failed
    }
}