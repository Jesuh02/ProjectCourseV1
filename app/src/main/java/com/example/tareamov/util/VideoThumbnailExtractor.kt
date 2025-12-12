package com.example.tareamov.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Extractor profesional de miniaturas desde frames de video
 * Soporta videos locales y URLs remotas (Cloudflare R2)
 */
class VideoThumbnailExtractor(private val context: Context) {
    
    companion object {
        private const val TAG = "VideoThumbnailExtractor"
        private const val DEFAULT_FRAME_TIME_MS = 1000L // 1 segundo en el video
        private const val THUMBNAIL_WIDTH = 1280
        private const val THUMBNAIL_HEIGHT = 720
        private const val JPEG_QUALITY = 90
    }
    
    /**
     * Extrae un frame del video y lo guarda como miniatura
     * @param videoUri URI del video (puede ser local o remota)
     * @param frameTimeMs Tiempo en milisegundos donde extraer el frame (default: 1s)
     * @return URI de la miniatura generada o null si falla
     */
    fun extractThumbnailFromVideo(
        videoUri: Uri,
        frameTimeMs: Long = DEFAULT_FRAME_TIME_MS
    ): Uri? {
        var retriever: MediaMetadataRetriever? = null
        
        try {
            Log.d(TAG, "🎬 Extrayendo miniatura del video: $videoUri")
            Log.d(TAG, "   Frame time: ${frameTimeMs}ms")
            
            retriever = MediaMetadataRetriever()
            
            // Configurar el retriever según el tipo de URI
            when {
                videoUri.scheme == "content" -> {
                    retriever.setDataSource(context, videoUri)
                }
                videoUri.scheme == "file" -> {
                    retriever.setDataSource(videoUri.path)
                }
                videoUri.scheme == "http" || videoUri.scheme == "https" -> {
                    retriever.setDataSource(videoUri.toString(), HashMap())
                }
                else -> {
                    retriever.setDataSource(context, videoUri)
                }
            }
            
            // Obtener duración del video
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Log.d(TAG, "   Video duration: ${duration}ms")
            
            // Ajustar el tiempo del frame si es mayor que la duración
            val actualFrameTime = if (frameTimeMs > duration && duration > 0) {
                duration / 2 // Tomar el frame del medio si el tiempo solicitado excede la duración
            } else {
                frameTimeMs
            }
            
            Log.d(TAG, "   Extracting frame at: ${actualFrameTime}ms")
            
            // Extraer el frame
            val bitmap = retriever.getFrameAtTime(
                actualFrameTime * 1000, // Convertir a microsegundos
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            
            if (bitmap == null) {
                Log.e(TAG, "❌ No se pudo extraer el frame del video")
                return null
            }
            
            Log.d(TAG, "✅ Frame extraído: ${bitmap.width}x${bitmap.height}")
            
            // Escalar la imagen a resolución óptima para miniatura
            val scaledBitmap = scaleBitmap(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            
            // Guardar la miniatura en almacenamiento temporal
            val thumbnailFile = createThumbnailFile()
            val outputStream = FileOutputStream(thumbnailFile)
            
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            outputStream.flush()
            outputStream.close()
            
            // Limpiar bitmaps
            if (bitmap != scaledBitmap) {
                bitmap.recycle()
            }
            scaledBitmap.recycle()
            
            val thumbnailUri = Uri.fromFile(thumbnailFile)
            Log.d(TAG, "✅ Miniatura guardada: $thumbnailUri")
            Log.d(TAG, "   Tamaño archivo: ${thumbnailFile.length()} bytes")
            
            return thumbnailUri
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extrayendo miniatura del video", e)
            return null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando MediaMetadataRetriever", e)
            }
        }
    }
    
    /**
     * Escala un bitmap manteniendo la proporción de aspecto
     */
    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Si ya está en el tamaño correcto, retornar el original
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }
        
        // Calcular la escala manteniendo la proporción
        val aspectRatio = width.toFloat() / height.toFloat()
        val targetAspectRatio = maxWidth.toFloat() / maxHeight.toFloat()
        
        val (targetWidth, targetHeight) = if (aspectRatio > targetAspectRatio) {
            // Video más ancho: ajustar al ancho máximo
            maxWidth to (maxWidth / aspectRatio).toInt()
        } else {
            // Video más alto: ajustar a la altura máxima
            (maxHeight * aspectRatio).toInt() to maxHeight
        }
        
        Log.d(TAG, "   Escalando de ${width}x${height} a ${targetWidth}x${targetHeight}")
        
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
    
    /**
     * Crea un archivo temporal para guardar la miniatura
     */
    private fun createThumbnailFile(): File {
        val thumbnailsDir = File(context.cacheDir, "video_thumbnails")
        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }
        
        val timestamp = System.currentTimeMillis()
        return File(thumbnailsDir, "thumbnail_$timestamp.jpg")
    }
    
    /**
     * Limpia miniaturas antiguas del caché
     */
    fun cleanOldThumbnails(maxAgeMs: Long = 24 * 60 * 60 * 1000) { // 24 horas por defecto
        try {
            val thumbnailsDir = File(context.cacheDir, "video_thumbnails")
            if (!thumbnailsDir.exists()) return
            
            val currentTime = System.currentTimeMillis()
            var deletedCount = 0
            
            thumbnailsDir.listFiles()?.forEach { file ->
                if (currentTime - file.lastModified() > maxAgeMs) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
            
            if (deletedCount > 0) {
                Log.d(TAG, "🧹 Limpiadas $deletedCount miniaturas antiguas")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error limpiando miniaturas antiguas", e)
        }
    }
}

