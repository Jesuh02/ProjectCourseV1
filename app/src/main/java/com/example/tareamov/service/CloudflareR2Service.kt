package com.example.tareamov.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tareamov.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

/**
 * Servicio para subir y descargar archivos usando Cloudflare R2
 * R2 es compatible con la API de AWS S3 y ofrece:
 * - 10 GB de almacenamiento GRATIS
 * - Egress (descarga) GRATIS - $0
 * - 10 millones de operaciones gratis/mes
 */
object CloudflareR2Service {
    
    private const val TAG = "CloudflareR2"
    
    // Cloudflare R2 Configuration from BuildConfig
    private val ACCOUNT_ID = BuildConfig.R2_ACCOUNT_ID
    private val ACCESS_KEY_ID = BuildConfig.R2_ACCESS_KEY_ID
    private val SECRET_ACCESS_KEY = BuildConfig.R2_SECRET_ACCESS_KEY
    private val BUCKET_NAME = BuildConfig.R2_BUCKET_NAME.ifEmpty { "coursev-files" }
    private val R2_ENDPOINT = BuildConfig.R2_ENDPOINT.ifEmpty { 
        "https://$ACCOUNT_ID.r2.cloudflarestorage.com" 
    }
    
    // Public URL base - Configure this after enabling public access in R2 dashboard
    // You can set a custom domain or use the default pub-*.r2.dev URL
    private const val DEFAULT_PUBLIC_URL = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev"
    private var publicUrlBase: String? = DEFAULT_PUBLIC_URL
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS) // 5 min for large video files
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    /**
     * Verifica si el servicio R2 está configurado correctamente
     */
    fun isConfigured(): Boolean {
        val configured = ACCOUNT_ID.isNotEmpty() && 
                        ACCESS_KEY_ID.isNotEmpty() && 
                        SECRET_ACCESS_KEY.isNotEmpty()
        Log.d(TAG, "R2 configured: $configured (AccountID: ${ACCOUNT_ID.take(8)}...)")
        return configured
    }
    
    /**
     * Configura la URL base pública para acceso a archivos
     * Llamar después de habilitar acceso público en R2 dashboard
     */
    fun setPublicUrlBase(url: String) {
        publicUrlBase = url.trimEnd('/')
        Log.d(TAG, "Public URL base set to: $publicUrlBase")
    }
    
    /**
     * Obtiene la URL pública de un archivo
     * Si no hay URL base configurada, devuelve la URL de R2 endpoint
     */
    fun getPublicUrl(objectKey: String): String {
        return publicUrlBase?.let { "$it/$objectKey" }
            ?: "$R2_ENDPOINT/$BUCKET_NAME/$objectKey"
    }
    
    /**
     * Obtiene la URL base pública actual
     */
    fun getPublicUrlBase(): String {
        return publicUrlBase ?: DEFAULT_PUBLIC_URL
    }
    
    /**
     * Verifica si una URL es de Cloudflare R2
     */
    fun isR2Url(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return url.contains(".r2.dev") || 
               url.contains(".r2.cloudflarestorage.com") ||
               url.contains("pub-9f393625246c4018b5613be60b01bda1")
    }
    
    /**
     * Convierte una URI de video local almacenada en Supabase a URL pública de R2
     * Los videos subidos se guardan en la carpeta "videos/" en R2
     * @param videoUriString La URI del video (puede ser local o ya una URL de R2)
     * @return URL pública de R2 si el video está disponible, null si no
     */
    fun getVideoStreamUrl(videoUriString: String?): String? {
        if (videoUriString.isNullOrEmpty()) return null
        
        // Si ya es una URL de R2, devolverla directamente
        if (isR2Url(videoUriString)) {
            return videoUriString
        }
        
        // Si es una URL HTTP/HTTPS válida (no de R2), devolverla
        if (videoUriString.startsWith("http://") || videoUriString.startsWith("https://")) {
            return videoUriString
        }
        
        // Para URIs locales (file://), intentar extraer el nombre del archivo
        // y construir la URL de R2
        if (videoUriString.startsWith("file://")) {
            val fileName = videoUriString.substringAfterLast("/")
            if (fileName.isNotEmpty()) {
                // Intentar encontrar el video en R2 por nombre de archivo
                return "${getPublicUrlBase()}/videos/$fileName"
            }
        }
        
        return null
    }
    
    /**
     * Genera una URL de streaming para un video dado su object key en R2
     */
    fun getVideoUrl(objectKey: String): String {
        return "${getPublicUrlBase()}/$objectKey"
    }
    
    /**
     * Sube un archivo a Cloudflare R2
     * @param context Context de Android
     * @param fileUri URI del archivo local
     * @param folder Carpeta destino (ej: "videos", "documents", "images", "submissions")
     * @param customFileName Nombre personalizado (opcional)
     * @param onProgress Callback para progreso de subida (opcional)
     * @return UploadResult con URL o error
     */
    suspend fun uploadFile(
        context: Context,
        fileUri: Uri,
        folder: String = "uploads",
        customFileName: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                return@withContext UploadResult.Error("Cloudflare R2 no está configurado. Verifica las credenciales en local.properties")
            }
            
            Log.d(TAG, "📤 Starting upload for URI: $fileUri to folder: $folder")
            onProgress?.invoke(5)
            
            // Leer el archivo
            val inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext UploadResult.Error("No se pudo abrir el archivo")
            
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            if (bytes.isEmpty()) {
                return@withContext UploadResult.Error("El archivo está vacío")
            }
            
            val fileSizeKB = bytes.size / 1024
            val fileSizeMB = fileSizeKB / 1024.0
            Log.d(TAG, "📦 File size: $fileSizeKB KB (${String.format("%.2f", fileSizeMB)} MB)")
            onProgress?.invoke(15)
            
            // Generar nombre único
            val originalName = getFileName(context, fileUri)
            val extension = originalName.substringAfterLast(".", "").lowercase()
            val sanitizedName = customFileName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                ?: UUID.randomUUID().toString()
            val finalFileName = if (extension.isNotEmpty()) "$sanitizedName.$extension" else sanitizedName
            val objectKey = "$folder/$finalFileName"
            
            // Detectar tipo MIME
            val mimeType = context.contentResolver.getType(fileUri) ?: getMimeType(extension)
            
            Log.d(TAG, "📝 Uploading as: $objectKey (MIME: $mimeType)")
            onProgress?.invoke(25)
            
            // Construir la solicitud con firma AWS Signature V4
            val url = "$R2_ENDPOINT/$BUCKET_NAME/$objectKey"
            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val host = "${ACCOUNT_ID}.r2.cloudflarestorage.com"
            
            // Hash del contenido
            val contentHash = sha256Hex(bytes)
            onProgress?.invoke(35)
            
            // Crear firma AWS Signature V4
            val authorization = createAuthorizationHeader(
                method = "PUT",
                uri = "/$BUCKET_NAME/$objectKey",
                host = host,
                date = date,
                dateStamp = dateStamp,
                contentHash = contentHash,
                contentType = mimeType
            )
            onProgress?.invoke(45)
            
            val requestBody = bytes.toRequestBody(mimeType.toMediaType())
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .addHeader("Host", host)
                .addHeader("x-amz-date", date)
                .addHeader("x-amz-content-sha256", contentHash)
                .addHeader("Content-Type", mimeType)
                .addHeader("Authorization", authorization)
                .build()
            
            Log.d(TAG, "🚀 Sending request to R2...")
            onProgress?.invoke(50)
            
            val response = client.newCall(request).execute()
            onProgress?.invoke(90)
            
            if (response.isSuccessful) {
                // Construir URL para acceso
                val accessUrl = getPublicUrl(objectKey)
                Log.d(TAG, "✅ File uploaded successfully: $accessUrl")
                onProgress?.invoke(100)
                return@withContext UploadResult.Success(
                    url = accessUrl,
                    objectKey = objectKey,
                    fileName = finalFileName,
                    fileSize = bytes.size.toLong(),
                    mimeType = mimeType
                )
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "❌ Upload failed: ${response.code} - $errorBody")
                return@withContext UploadResult.Error("Error ${response.code}: $errorBody")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during upload", e)
            return@withContext UploadResult.Error("Error: ${e.message}")
        }
    }
    
    /**
     * Sube una imagen a R2
     */
    suspend fun uploadImage(
        context: Context,
        imageUri: Uri,
        customFileName: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        return uploadFile(context, imageUri, "images", customFileName, onProgress)
    }
    
    /**
     * Sube una miniatura de curso a R2
     * @param context Context de Android
     * @param thumbnailUri URI de la imagen de miniatura
     * @param courseId ID del curso (opcional, para nombrar el archivo)
     * @param onProgress Callback para progreso de subida
     */
    suspend fun uploadThumbnail(
        context: Context,
        thumbnailUri: Uri,
        courseId: Long? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        val timestamp = System.currentTimeMillis()
        val customName = if (courseId != null) "course_${courseId}_$timestamp" else "thumb_$timestamp"
        return uploadFile(context, thumbnailUri, "thumbnails/courses", customName, onProgress)
    }
    
    /**
     * Sube un video a R2
     */
    suspend fun uploadVideo(
        context: Context,
        videoUri: Uri,
        customFileName: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        return uploadFile(context, videoUri, "videos", customFileName, onProgress)
    }
    
    /**
     * Sube un documento (PDF, DOCX, etc.) a R2
     */
    suspend fun uploadDocument(
        context: Context,
        documentUri: Uri,
        customFileName: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        return uploadFile(context, documentUri, "documents", customFileName, onProgress)
    }
    
    /**
     * Sube un archivo de submission de tarea a R2
     */
    suspend fun uploadSubmission(
        context: Context,
        fileUri: Uri,
        taskId: Long,
        username: String,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        val folder = "submissions/task_$taskId"
        val timestamp = System.currentTimeMillis()
        val customName = "${username}_${timestamp}"
        return uploadFile(context, fileUri, folder, customName, onProgress)
    }
    
    /**
     * Sube contenido de curso (video/documento de tarea o tema) a R2
     */
    suspend fun uploadCourseContent(
        context: Context,
        fileUri: Uri,
        courseId: Long,
        contentType: String, // "video" o "document"
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        val folder = "courses/course_$courseId/$contentType"
        return uploadFile(context, fileUri, folder, null, onProgress)
    }
    
    /**
     * Elimina un archivo de R2
     */
    suspend fun deleteFile(objectKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.e(TAG, "R2 not configured for delete operation")
                return@withContext false
            }
            
            val url = "$R2_ENDPOINT/$BUCKET_NAME/$objectKey"
            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val host = "${ACCOUNT_ID}.r2.cloudflarestorage.com"
            val contentHash = sha256Hex(ByteArray(0))
            
            val authorization = createAuthorizationHeader(
                method = "DELETE",
                uri = "/$BUCKET_NAME/$objectKey",
                host = host,
                date = date,
                dateStamp = dateStamp,
                contentHash = contentHash,
                contentType = ""
            )
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("Host", host)
                .addHeader("x-amz-date", date)
                .addHeader("x-amz-content-sha256", contentHash)
                .addHeader("Authorization", authorization)
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful || response.code == 204
            
            if (success) {
                Log.d(TAG, "✅ File deleted: $objectKey")
            } else {
                Log.e(TAG, "❌ Delete failed: ${response.code}")
            }
            
            return@withContext success
            
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file", e)
            return@withContext false
        }
    }
    
    // ========== AWS Signature V4 Implementation ==========
    
    private fun getAmzDate(): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
    
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
    
    private fun createAuthorizationHeader(
        method: String,
        uri: String,
        host: String,
        date: String,
        dateStamp: String,
        contentHash: String,
        contentType: String
    ): String {
        val region = "auto"
        val service = "s3"
        
        // Canonical headers - must be sorted alphabetically
        val signedHeaders: String
        val canonicalHeaders: String
        
        if (contentType.isNotEmpty()) {
            signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date"
            canonicalHeaders = "content-type:$contentType\nhost:$host\nx-amz-content-sha256:$contentHash\nx-amz-date:$date\n"
        } else {
            signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            canonicalHeaders = "host:$host\nx-amz-content-sha256:$contentHash\nx-amz-date:$date\n"
        }
        
        // Canonical request
        val canonicalRequest = "$method\n$uri\n\n$canonicalHeaders\n$signedHeaders\n$contentHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray())
        
        // String to sign
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$date\n$credentialScope\n$canonicalRequestHash"
        
        // Signing key
        val kDate = hmacSha256("AWS4$SECRET_ACCESS_KEY".toByteArray(), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        val kSigning = hmacSha256(kService, "aws4_request")
        
        // Signature
        val signature = hmacSha256(kSigning, stringToSign).joinToString("") { "%02x".format(it) }
        
        return "AWS4-HMAC-SHA256 Credential=$ACCESS_KEY_ID/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    }
    
    private fun getFileName(context: Context, uri: Uri): String {
        var name = "file"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: "file"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get file name", e)
        }
        return name.replace(" ", "_").replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
    
    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            
            // Videos
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            
            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            
            // Documents
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            
            // Code
            "kt" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "py" -> "text/x-python"
            "sql" -> "application/sql"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            
            else -> "application/octet-stream"
        }
    }
    
    /**
     * Resultado de operación de subida
     */
    sealed class UploadResult {
        data class Success(
            val url: String,
            val objectKey: String,
            val fileName: String,
            val fileSize: Long,
            val mimeType: String
        ) : UploadResult()
        
        data class Error(val message: String) : UploadResult()
        
        fun isSuccess(): Boolean = this is Success
        
        fun getUrlOrNull(): String? = (this as? Success)?.url
        
        fun getErrorMessage(): String? = (this as? Error)?.message
    }
}
