package com.example.tareamov.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * Drop-in replacement for the deleted CloudflareR2Service.
 * All storage operations are routed through the backend API
 * (BackendApiService → /api/v1/storage endpoints) so the Android app
 * never talks directly to Cloudflare R2.
 */
object StorageHelper {

    private const val TAG = "StorageHelper"

    // ───────────────────────── UploadResult sealed class ─────────────────────────
    sealed class UploadResult {
        data class Success(val url: String) : UploadResult()
        data class Error(val message: String) : UploadResult()
    }

    // ───────────────────────── URL helpers ─────────────────────────

    /**
     * Returns true when [url] points to a Cloudflare R2 bucket.
     */
    fun isR2Url(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return url.contains(".r2.dev") ||
                url.contains(".r2.cloudflarestorage.com") ||
                url.contains("pub-")
    }

    /**
     * The backend is always available, so this always returns true.
     */
    fun isConfigured(): Boolean = true

    // ───────────────────────── Signed-URL resolution ─────────────────────────

    /**
     * Obtain a time-limited signed URL for a private R2 object.
     * Delegates to the backend endpoint POST /video/signed-url through [MCPHttpClient].
     */
    suspend fun getVideoStreamUrl(context: Context, uriString: String?): String? {
        if (uriString.isNullOrEmpty()) return null
        // Already signed → reuse
        if (uriString.startsWith("http") && uriString.contains("X-Amz-Signature")) return uriString
        return try {
            MCPHttpClient(context).getSignedUrl(uriString)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting signed URL for: $uriString", e)
            null
        }
    }

    // ───────────────────────── Upload helpers ─────────────────────────

    /**
     * Generic file upload via POST /storage/upload (multipart).
     * Matches the old CloudflareR2Service.uploadFile signature.
     *
     * @param context       Android context (for ContentResolver).
     * @param fileUri       Content/file URI of the file to upload.
     * @param folder        Destination folder inside R2 (e.g. "thumbnails/courses").
     * @param customFileName Optional custom filename (without extension).
     * @param onProgress    Optional progress callback (0-100). Currently not granular
     *                      because the backend handles the actual R2 transfer.
     */
    suspend fun uploadFile(
        context: Context,
        fileUri: Uri,
        folder: String,
        customFileName: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        return try {
            onProgress?.invoke(0)

            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(fileUri) ?: "application/octet-stream"
            val originalName = getFileNameFromUri(context, fileUri) ?: "file_${System.currentTimeMillis()}"
            val fileName = if (customFileName != null) {
                // Preserve extension from original file
                val ext = originalName.substringAfterLast('.', "")
                if (ext.isNotEmpty() && !customFileName.contains('.')) "$customFileName.$ext" else customFileName
            } else {
                originalName
            }

            onProgress?.invoke(30)

            BackendApiService.initialize(context)
            val result = BackendApiService.uploadFile(
                context = context,
                fileUri = fileUri,
                fileName = fileName,
                mimeType = mimeType,
                folder = folder,
                onProgress = { progress ->
                    val mappedProgress = 30 + (progress * 0.7).toInt()
                    onProgress?.invoke(mappedProgress.coerceIn(30, 100))
                }
            )

            onProgress?.invoke(100)

            when (result) {
                is ApiResult.Success -> {
                    val data = result.data
                    val url = data?.get("publicUrl")?.asString
                        ?: data?.get("url")?.asString
                        ?: data?.get("key")?.asString
                        ?: return UploadResult.Error("No URL in upload response")
                    Log.d(TAG, "✅ Upload successful: $url")
                    UploadResult.Success(url)
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "❌ Upload failed: ${result.message}")
                    UploadResult.Error(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during upload", e)
            UploadResult.Error(e.message ?: "Upload failed")
        }
    }

    /**
     * Upload a video file.
     * Convenience alias that defaults the folder to "videos".
     */
    suspend fun uploadVideo(
        context: Context,
        videoUri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult = uploadFile(context, videoUri, "videos", onProgress = onProgress)

    /**
     * Upload a document file.
     * Convenience alias that defaults the folder to "documents".
     */
    suspend fun uploadDocument(
        context: Context,
        documentUri: Uri,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult = uploadFile(context, documentUri, "documents", onProgress = onProgress)

    /**
     * Upload an image file (e.g. avatar, thumbnail).
     * Convenience alias that defaults the folder to "images".
     *
     * @param customFileName can include a sub-path like "avatars/avatar_123.jpg".
     */
    suspend fun uploadImage(
        context: Context,
        imageUri: Uri,
        customFileName: String,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult = uploadFile(context, imageUri, "images", customFileName, onProgress)

    /**
     * Upload a thumbnail image.
     */
    suspend fun uploadThumbnail(
        context: Context,
        imageUri: Uri,
        customFileName: String? = null
    ): UploadResult = uploadFile(context, imageUri, "thumbnails", customFileName)

    // ───────────────────────── Delete ─────────────────────────

    /**
     * Delete a remote file by its URL or R2 object key.
     * Routes through DELETE /storage?key=…
     *
     * @return true if the backend confirmed deletion, false otherwise.
     */
    suspend fun deleteFile(urlOrKey: String): Boolean {
        return try {
            val key = extractObjectKey(urlOrKey)
            val result = BackendApiService.deleteStorageFile(key)
            val ok = result is ApiResult.Success
            if (ok) Log.d(TAG, "🗑️ Deleted: $key") else Log.w(TAG, "Delete failed for: $key")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: $urlOrKey", e)
            false
        }
    }

    // ───────────────────────── Internal helpers ─────────────────────────

    private fun extractObjectKey(url: String): String {
        if (!url.startsWith("http")) return url
        return try {
            val uri = Uri.parse(url)
            val path = uri.path ?: return url
            // Strip leading slash and optional bucket-name prefix
            val hostPrefix = uri.host?.split(".")?.getOrNull(0)
            val trimmed = path.trimStart('/')
            if (!hostPrefix.isNullOrEmpty() && trimmed.startsWith("$hostPrefix/")) {
                trimmed.substringAfter("$hostPrefix/")
            } else {
                trimmed
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        return uri.lastPathSegment
    }
}
