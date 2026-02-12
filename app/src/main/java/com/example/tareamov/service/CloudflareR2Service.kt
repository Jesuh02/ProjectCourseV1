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
    
    // Configuración para el bucket de miniaturas (Público)
    private const val THUMBNAIL_BUCKET_NAME = "coursev-fil"
    private const val THUMBNAIL_PUBLIC_URL_BASE = "https://pub-4e815af1d00c464d999d446ba4c03d07.r2.dev"
    
    // ==================== SIGNED URL CACHE ====================
    // Cache de URLs firmadas para evitar llamadas repetidas al backend.
    // Las URLs firmadas expiran en 1 hora (3600s) en el backend;
    // usamos un TTL de 50 minutos para tener margen de seguridad.
    private const val SIGNED_URL_CACHE_TTL_MS = 50L * 60 * 1000 // 50 minutos
    
    private data class CachedSignedUrl(
        val url: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = (System.currentTimeMillis() - timestamp) > SIGNED_URL_CACHE_TTL_MS
    }
    
    // Thread-safe cache: objectKey → CachedSignedUrl
    private val signedUrlCache = java.util.concurrent.ConcurrentHashMap<String, CachedSignedUrl>()
    
    /**
     * Limpia todas las URLs firmadas cacheadas.
     * Útil al hacer logout o cambiar de cuenta.
     */
    fun clearSignedUrlCache() {
        signedUrlCache.clear()
        Log.d(TAG, "🗑️ Signed URL cache cleared")
    }
    
    /**
     * Extrae el object key de una URL firmada de R2.
     * Ejemplo: "https://xxx.r2.dev/videos/file.mp4?X-Amz-..." → "videos/file.mp4"
     * Retorna null si no es una URL de R2.
     */
    fun extractObjectKeyFromSignedUrl(signedUrl: String): String? {
        if (!isR2Url(signedUrl) && !signedUrl.contains("X-Amz-")) return null
        return try {
            val uri = Uri.parse(signedUrl)
            uri.path?.trimStart('/')
        } catch (e: Exception) {
            null
        }
    }
    
    // OkHttp client con configuración optimizada para subidas grandes
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS) // 10 min for large video files
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    /**
     * Prueba la conectividad con R2 haciendo un HEAD request al bucket
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.e(TAG, "❌ R2 not configured for connection test")
                return@withContext false
            }
            
            val host = "$BUCKET_NAME.$ACCOUNT_ID.r2.cloudflarestorage.com"
            val url = "https://$host/"
            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val contentHash = sha256Hex(ByteArray(0))
            
            val authorization = createAuthorizationHeader(
                method = "HEAD",
                uri = "/",
                host = host,
                date = date,
                dateStamp = dateStamp,
                contentHash = contentHash,
                contentType = ""
            )
            
            val request = Request.Builder()
                .url(url)
                .head()
                .header("Host", host)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", contentHash)
                .header("Authorization", authorization)
                .build()
            
            Log.d(TAG, "🔌 Testing R2 connection to: $url")
            val response = client.newCall(request).execute()
            val success = response.isSuccessful || response.code == 200 || response.code == 404
            
            Log.d(TAG, if (success) "✅ R2 connection test PASSED (${response.code})" else "❌ R2 connection test FAILED (${response.code})")
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "❌ R2 connection test exception: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Verifica si el servicio R2 está configurado correctamente
     */
    fun isConfigured(): Boolean {
        val accountOk = ACCOUNT_ID.isNotEmpty()
        val accessKeyOk = ACCESS_KEY_ID.isNotEmpty()
        val secretKeyOk = SECRET_ACCESS_KEY.isNotEmpty()
        val configured = accountOk && accessKeyOk && secretKeyOk
        
        Log.d(TAG, "🔍 R2 Configuration Check:")
        Log.d(TAG, "   ACCOUNT_ID: ${if (accountOk) "${ACCOUNT_ID.take(8)}..." else "❌ EMPTY"}")
        Log.d(TAG, "   ACCESS_KEY_ID: ${if (accessKeyOk) "${ACCESS_KEY_ID.take(8)}..." else "❌ EMPTY"}")
        Log.d(TAG, "   SECRET_ACCESS_KEY: ${if (secretKeyOk) "✅ SET (${SECRET_ACCESS_KEY.length} chars)" else "❌ EMPTY"}")
        Log.d(TAG, "   BUCKET_NAME: $BUCKET_NAME")
        Log.d(TAG, "   R2_ENDPOINT: $R2_ENDPOINT")
        Log.d(TAG, "   Result: ${if (configured) "✅ CONFIGURED" else "❌ NOT CONFIGURED"}")
        
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
     * Obtiene una URL pública optimizada para streaming de video
     * Agrega parámetros de optimización si están disponibles
     * @param objectKey La clave del objeto en R2
     * @param quality Calidad del video (opcional: "auto", "720p", "480p", "360p")
     * @return URL pública optimizada para streaming
     */
    fun getVideoStreamingUrl(objectKey: String, quality: String = "auto"): String {
        val baseUrl = getPublicUrl(objectKey)
        
        // Si Cloudflare Stream está habilitado, agregar parámetros de optimización
        // Por ahora, devolver URL directa que funciona con todos los reproductores
        return baseUrl
    }
    
    /**
     * Genera una URL de vista previa (thumbnail) para un video
     * Si el video está en R2, intenta generar una URL de thumbnail
     * @param videoUrl URL del video
     * @param timeInSeconds Tiempo en segundos del video donde tomar el thumbnail (default: 0)
     * @return URL del thumbnail o null si no está disponible
     */
    fun getVideoThumbnailUrl(videoUrl: String, timeInSeconds: Int = 0): String? {
        return try {
            // Si es una URL de R2, podemos intentar generar un thumbnail
            if (isR2Url(videoUrl)) {
                // Para R2 básico sin Cloudflare Stream, no hay thumbnails automáticos
                // Devolver null y dejar que la app use el frame del video
                null
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generando thumbnail URL: ${e.message}")
            null
        }
    }
    
    /**
     * Crea una URL de compartir optimizada con información adicional
     * Esta URL incluye metadatos para previsualizaciones en redes sociales (Open Graph)
     * @param context Contexto necesario para obtener URLs firmadas
     * @param videoData El video a compartir
     * @return URL formateada para compartir
     */
    suspend fun createShareableVideoUrl(context: Context, videoUrl: String, title: String, description: String = ""): String {
        // Si ya es una URL pública de R2, devolverla con contexto adicional
        if (isR2Url(videoUrl)) {
            return videoUrl
        }
        
        // Si no, intentar convertir a URL pública
        val publicUrl = getVideoStreamUrl(context, videoUrl)
        return publicUrl ?: videoUrl
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
               url.contains("pub-9f393625246c4018b5613be60b01bda1") ||
               url.contains("pub-4e815af1d00c464d999d446ba4c03d07")
    }
    
    /**
     * Convierte una URI de video local almacenada en Supabase a URL pública de R2
     * Los videos subidos se guardan en la carpeta "videos/" en R2
     * @param videoUriString La URI del video (puede ser local o ya una URL de R2)
     * @return URL firmada de R2 si es contenido privado, o la URL original
     */
    suspend fun getVideoStreamUrl(context: Context, videoUriString: String?): String? {
        if (videoUriString.isNullOrEmpty()) return null
        
        var objectKey: String? = null
        
        // Caso 1: URL completa antigua de R2 (contiene el dominio r2.dev)
        if (isR2Url(videoUriString)) {
            val uri = Uri.parse(videoUriString)
            objectKey = uri.path?.trimStart('/')
        }
        // Caso 2: Ruta relativa limpia "videos/archivo.mp4" (guardada tras migración DB)
        else if (!videoUriString.startsWith("http://") && !videoUriString.startsWith("https://") && !videoUriString.startsWith("file://") && !videoUriString.startsWith("content://")) {
             objectKey = videoUriString
        }
        // Caso 3: Fallback legacy para file:// que asume que el archivo también existe remotamente
        else if (videoUriString.startsWith("file://")) {
            val fileName = videoUriString.substringAfterLast("/")
            if (fileName.isNotEmpty()) {
                objectKey = "videos/$fileName"
            }
        }
        
        if (objectKey != null) {
             // Verificar caché primero
             val cached = signedUrlCache[objectKey]
             if (cached != null && !cached.isExpired()) {
                 Log.d(TAG, "✅ Signed URL desde caché para key: $objectKey")
                 return cached.url
             }
             
             Log.d(TAG, "Solicitando Signed URL para key: $objectKey (cache miss)")
             try {
                // Instanciar MCPHttpClient - asegurarse que la clase es accesible
                val mcpClient = MCPHttpClient(context)
                val signedUrl = mcpClient.getSignedUrl(objectKey)
                if (!signedUrl.isNullOrEmpty()) {
                    // Guardar en caché
                    signedUrlCache[objectKey] = CachedSignedUrl(signedUrl)
                    Log.d(TAG, "✅ Signed URL obtenida y cacheada (TTL=50min) para: $objectKey")
                    return signedUrl
                }
             } catch (e: Exception) {
                 Log.e(TAG, "Error obteniendo Signed URL", e)
             }
        }
        
        // Si no es R2 o falló la firma, devolver original (para URLs externas o compatibilidad)
        return videoUriString
    }
    
    /**
     * Genera una URL de streaming para un video dado su object key en R2
     */
    fun getVideoUrl(objectKey: String): String {
        return "${getPublicUrlBase()}/$objectKey"
    }
    
    /**
     * Sube un archivo a Cloudflare R2 usando streaming para archivos grandes
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
            
            // Obtener tamaño del archivo sin cargarlo completo
            val fileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "r")
                ?: return@withContext UploadResult.Error("No se pudo abrir el archivo")
            
            val fileSize = fileDescriptor.statSize
            fileDescriptor.close()
            
            if (fileSize <= 0) {
                return@withContext UploadResult.Error("El archivo está vacío")
            }
            
            val fileSizeKB = fileSize / 1024
            val fileSizeMB = fileSizeKB / 1024.0
            Log.d(TAG, "📦 File size: $fileSizeKB KB (${String.format("%.2f", fileSizeMB)} MB)")
            
            // Para archivos pequeños (<10MB), usar método tradicional
            // Para archivos grandes (>=10MB), usar streaming para evitar OutOfMemoryError
            if (fileSizeMB < 10.0) {
                Log.d(TAG, "Using standard upload for small file (<10MB)")
                val inputStream = context.contentResolver.openInputStream(fileUri)
                    ?: return@withContext UploadResult.Error("No se pudo abrir el archivo")
                
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                return@withContext uploadBytes(
                    context = context,
                    bytes = bytes,
                    fileUri = fileUri,
                    folder = folder,
                    customFileName = customFileName,
                    onProgress = onProgress
                )
            }
            
            // Streaming para archivos grandes
            Log.d(TAG, "Using streaming upload for large file")
            onProgress?.invoke(15)
            
            // Generar nombre único
            val originalName = getFileName(context, fileUri)
            val extension = originalName.substringAfterLast(".", "").lowercase()
            val sanitizedName = customFileName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                ?: UUID.randomUUID().toString()
            
            // Avoid double extensions
            val finalFileName = if (extension.isNotEmpty() && !sanitizedName.endsWith(".$extension", ignoreCase = true)) 
                "$sanitizedName.$extension" 
            else 
                sanitizedName
                
            val objectKey = "$folder/$finalFileName"
            
            // Detectar tipo MIME: Prefer resolved -> custom -> original extension
            var mimeType = context.contentResolver.getType(fileUri)
            if (mimeType == null) {
                val customExt = customFileName?.substringAfterLast(".", "")?.lowercase()
                if (!customExt.isNullOrEmpty()) {
                    mimeType = getMimeType(customExt)
                } else {
                    mimeType = getMimeType(extension)
                }
            }
            
            Log.d(TAG, "📝 Uploading as: $objectKey (MIME: $mimeType)")
            onProgress?.invoke(25)
            
            // Para streaming, usar UNSIGNED-PAYLOAD
            val host = "$BUCKET_NAME.$ACCOUNT_ID.r2.cloudflarestorage.com"
            val url = "https://$host/$objectKey"
            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val contentHash = "UNSIGNED-PAYLOAD"
            
            onProgress?.invoke(35)
            
            Log.d(TAG, "🔐 Creating AWS Signature V4...")
            Log.d(TAG, "   Host: $host")
            Log.d(TAG, "   URI: /$objectKey")
            Log.d(TAG, "   Date: $date")
            Log.d(TAG, "   Content Hash: ${contentHash.take(16)}...")
            
            // Crear firma AWS Signature V4
            val authorization = createAuthorizationHeader(
                method = "PUT",
                uri = "/$objectKey",  // Sin el bucket en la URI porque está en el host
                host = host,
                date = date,
                dateStamp = dateStamp,
                contentHash = contentHash,
                contentType = mimeType
            )
            onProgress?.invoke(45)
            
            Log.d(TAG, "   Authorization: ${authorization.take(80)}...")
            
            // Crear RequestBody desde InputStream para streaming
            val inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext UploadResult.Error("No se pudo abrir el archivo para streaming")
            
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = mimeType.toMediaType()
                override fun contentLength() = fileSize
                
                override fun writeTo(sink: okio.BufferedSink) {
                    val buffer = ByteArray(8192) // 8KB chunks
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Update progress
                        val progress = 50 + ((totalBytesRead.toFloat() / fileSize) * 40).toInt()
                        onProgress?.invoke(progress)
                    }
                    inputStream.close()
                }
            }
            
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Host", host)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", contentHash)
                .header("Content-Type", mimeType)
                .header("Content-Length", fileSize.toString())
                .header("Authorization", authorization)
                .build()
            
            Log.d(TAG, "🚀 Sending streaming request to R2...")
            Log.d(TAG, "   URL: $url")
            Log.d(TAG, "   Method: PUT")
            Log.d(TAG, "   Content-Length: $fileSize bytes")
            Log.d(TAG, "   Using UNSIGNED-PAYLOAD for streaming")
            
            val response = client.newCall(request).execute()
            onProgress?.invoke(90)
            
            Log.d(TAG, "📥 Response received: ${response.code} ${response.message}")
            
            if (response.isSuccessful) {
                // Construir URL para acceso público
                val accessUrl = getPublicUrl(objectKey)
                Log.d(TAG, "✅ File uploaded successfully!")
                Log.d(TAG, "   Access URL: $accessUrl")
                Log.d(TAG, "   Object Key: $objectKey")
                onProgress?.invoke(100)
                return@withContext UploadResult.Success(
                    url = accessUrl,
                    objectKey = objectKey,
                    fileName = finalFileName,
                    fileSize = fileSize,
                    mimeType = mimeType
                )
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "❌ Upload failed!")
                Log.e(TAG, "   Response Code: ${response.code}")
                Log.e(TAG, "   Response Message: ${response.message}")
                Log.e(TAG, "   Error Body: $errorBody")
                
                // Diagnóstico adicional según código de error
                val diagnosticMsg = when (response.code) {
                    400 -> "Bad Request - Verifica el formato de la solicitud"
                    403 -> "Forbidden - Verifica credenciales R2 (ACCESS_KEY_ID, SECRET_ACCESS_KEY)"
                    404 -> "Not Found - El bucket '$BUCKET_NAME' no existe o la URL está mal formada"
                    405 -> "Method Not Allowed - El bucket puede no aceptar PUT requests"
                    500, 502, 503 -> "Error del servidor R2 - Intenta más tarde"
                    else -> "Error desconocido"
                }
                Log.e(TAG, "   Diagnóstico: $diagnosticMsg")
                
                return@withContext UploadResult.Error("Error ${response.code}: $diagnosticMsg. $errorBody")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during upload", e)
            return@withContext UploadResult.Error("Error: ${e.message}")
        }
    }
    
    /**
     * Sube bytes directamente a R2 (para archivos pequeños ya cargados en memoria)
     */
    private suspend fun uploadBytes(
        context: Context,
        bytes: ByteArray,
        fileUri: Uri,
        folder: String,
        customFileName: String?,
        onProgress: ((Int) -> Unit)?
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(15)
            
            // Generar nombre único
            val originalName = getFileName(context, fileUri)
            val extension = originalName.substringAfterLast(".", "").lowercase()
            val sanitizedName = customFileName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                ?: UUID.randomUUID().toString()
                
            // Avoid double extensions
            val finalFileName = if (extension.isNotEmpty() && !sanitizedName.endsWith(".$extension", ignoreCase = true)) 
                "$sanitizedName.$extension" 
            else 
                sanitizedName
                
            val objectKey = "$folder/$finalFileName"
            
            // Detectar tipo MIME: Prefer resolved -> custom -> original extension
            var mimeType = context.contentResolver.getType(fileUri)
            if (mimeType == null) {
                val customExt = customFileName?.substringAfterLast(".", "")?.lowercase()
                if (!customExt.isNullOrEmpty()) {
                    mimeType = getMimeType(customExt)
                } else {
                    mimeType = getMimeType(extension)
                }
            }
            
            Log.d(TAG, "📝 Uploading as: $objectKey (MIME: $mimeType)")
            onProgress?.invoke(25)
            
            val host = "$BUCKET_NAME.$ACCOUNT_ID.r2.cloudflarestorage.com"
            val url = "https://$host/$objectKey"
            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val contentHash = sha256Hex(bytes)
            
            onProgress?.invoke(35)
            
            val authorization = createAuthorizationHeader(
                method = "PUT",
                uri = "/$objectKey",
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
                .header("Host", host)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", contentHash)
                .header("Content-Type", mimeType)
                .header("Authorization", authorization)
                .build()
            
            Log.d(TAG, "🚀 Sending request to R2...")
            onProgress?.invoke(50)
            
            val response = client.newCall(request).execute()
            onProgress?.invoke(90)
            
            if (response.isSuccessful) {
                val accessUrl = getPublicUrl(objectKey)
                Log.d(TAG, "✅ File uploaded successfully!")
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
     * Comprime una imagen a un tamaño máximo y calidad especificada
     */
    private suspend fun compressImage(
        context: Context,
        imageUri: Uri,
        maxWidth: Int = 1280,
        maxHeight: Int = 720,
        quality: Int = 85
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from URI: $imageUri")
                return@withContext null
            }
            
            // Calcular el tamaño escalado manteniendo aspect ratio
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height
            val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
            
            var targetWidth = originalWidth
            var targetHeight = originalHeight
            
            if (originalWidth > maxWidth || originalHeight > maxHeight) {
                if (aspectRatio > 1) {
                    // Landscape
                    targetWidth = maxWidth
                    targetHeight = (maxWidth / aspectRatio).toInt()
                } else {
                    // Portrait or square
                    targetHeight = maxHeight
                    targetWidth = (maxHeight * aspectRatio).toInt()
                }
            }
            
            Log.d(TAG, "Compressing image: ${originalWidth}x${originalHeight} -> ${targetWidth}x${targetHeight} @ quality $quality")
            
            // Escalar el bitmap
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap, targetWidth, targetHeight, true
            )
            
            // Comprimir a JPEG
            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
            val compressedBytes = outputStream.toByteArray()
            
            // Limpiar recursos
            bitmap.recycle()
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            outputStream.close()
            
            val compressedSizeKB = compressedBytes.size / 1024
            Log.d(TAG, "Compression complete: $compressedSizeKB KB")
            
            return@withContext compressedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image", e)
            return@withContext null
        }
    }
    
    /**
     * Sube una miniatura de curso a R2 (comprimida automáticamente)
     * @param context Contexto de Android
     * @param thumbnailUri URI de la miniatura
     * @param courseId ID del curso (opcional, se usa para el nombre del archivo)
     * @param onProgress Callback para progreso de subida
     */
    suspend fun uploadThumbnail(
        context: Context,
        thumbnailUri: Uri,
        courseId: Long? = null,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                return@withContext UploadResult.Error("Cloudflare R2 no está configurado")
            }
            
            Log.d(TAG, "📸 Compressing thumbnail before upload...")
            onProgress?.invoke(10)
            
            // Comprimir la imagen a 1280x720 @ 85% quality
            val compressedBytes = compressImage(context, thumbnailUri, 1280, 720, 85)
            if (compressedBytes == null) {
                return@withContext UploadResult.Error("Error comprimiendo la imagen")
            }
            
            onProgress?.invoke(20)
            
            // Generar nombre único
            val timestamp = System.currentTimeMillis()
            val customName = if (courseId != null) "course_${courseId}_$timestamp" else "thumb_$timestamp"
            val objectKey = "thumbnails/courses/$customName.jpg"
            val mimeType = "image/jpeg"
            
            Log.d(TAG, "📤 Uploading compressed thumbnail: $objectKey (${compressedBytes.size / 1024} KB)")
            onProgress?.invoke(30)
            
            // Subir usando firma AWS Signature V4 al bucket PÚBLICO de miniaturas
            val host = "$THUMBNAIL_BUCKET_NAME.$ACCOUNT_ID.r2.cloudflarestorage.com"
            val url = "https://$host/$objectKey"
            val publicUrl = "$THUMBNAIL_PUBLIC_URL_BASE/$objectKey"

            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val contentHash = sha256Hex(compressedBytes)
            
            val authorization = createAuthorizationHeader(
                method = "PUT",
                uri = "/$objectKey",
                host = host,
                date = date,
                dateStamp = dateStamp,
                contentHash = contentHash,
                contentType = mimeType
            )
            
            onProgress?.invoke(50)
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .put(compressedBytes.toRequestBody(mimeType.toMediaType()))
                .addHeader("Host", host)
                .addHeader("x-amz-date", date)
                .addHeader("x-amz-content-sha256", contentHash)
                .addHeader("Authorization", authorization)
                .addHeader("Content-Type", mimeType)
                .build()
            
            Log.d(TAG, "🌐 Sending PUT request to: $url")
            
            val response = client.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "📥 Response code: $responseCode")
            
            onProgress?.invoke(100)
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Thumbnail uploaded successfully: $publicUrl")
                return@withContext UploadResult.Success(
                    url = publicUrl,
                    objectKey = objectKey,
                    fileName = "$customName.jpg",
                    fileSize = compressedBytes.size.toLong(),
                    mimeType = mimeType
                )
            } else {
                Log.e(TAG, "❌ Upload failed: $responseCode - $responseBody")
                return@withContext UploadResult.Error("Upload failed: HTTP $responseCode - $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception uploading thumbnail", e)
            return@withContext UploadResult.Error("Exception: ${e.message}")
        }
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
            
            // Usar el formato de virtual-hosted style
            val host = "$BUCKET_NAME.$ACCOUNT_ID.r2.cloudflarestorage.com"
            val url = "https://$host/$objectKey"
            val date = getAmzDate()
            val dateStamp = date.substring(0, 8)
            val contentHash = sha256Hex(ByteArray(0))
            
            val authorization = createAuthorizationHeader(
                method = "DELETE",
                uri = "/$objectKey",
                host = host,
                date = date,
                dateStamp = dateStamp,
                contentHash = contentHash,
                contentType = ""
            )
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .header("Host", host)
                .header("x-amz-date", date)
                .header("x-amz-content-sha256", contentHash)
                .header("Authorization", authorization)
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful || response.code == 204
            
            if (success) {
                Log.d(TAG, "✅ File deleted: $objectKey")
            } else {
                Log.e(TAG, "❌ Delete failed: ${response.code} - ${response.body?.string()}")
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
