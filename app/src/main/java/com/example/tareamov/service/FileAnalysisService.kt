package com.example.tareamov.service

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class FileAnalysisService(private val context: Context) {

    suspend fun extractFileContent(uri: Uri, fileName: String): FileAnalysisResult = withContext(Dispatchers.IO) {
        try {
            Log.d("FileAnalysisService", "Iniciando extracción de archivo: $fileName")
            Log.d("FileAnalysisService", "URI: $uri")
            Log.d("FileAnalysisService", "Esquema URI: ${uri.scheme}")
            // Extracción SIEMPRE dinámica, usando el archivo seleccionado por el usuario
            val contentResolver = context.contentResolver
            val isGoogleDriveUri = uri.authority?.contains("google") == true || 
                                  uri.authority?.contains("docs") == true
            if (isGoogleDriveUri) {
                Log.d("FileAnalysisService", "Detectado URI de Google Drive - Usando manejo especial")
                return@withContext handleGoogleDriveFile(uri, fileName)
            }
            if (!isUriAccessible(uri)) {
                Log.e("FileAnalysisService", "URI no accesible o archivo no disponible")
                return@withContext FileAnalysisResult(
                    success = false,
                    error = "No se pudo acceder al archivo. Si es un archivo de Google Drive, descárgalo a tu dispositivo primero.",
                    fileType = getFileType(fileName)
                )
            }
            val inputStream = try {
                contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                Log.e("FileAnalysisService", "Error abriendo InputStream: ${e.message}")
                null
            }
            if (inputStream == null) {
                Log.e("FileAnalysisService", "No se pudo abrir el archivo: $fileName")
                return@withContext FileAnalysisResult(
                    success = false,
                    error = "No se pudo acceder al archivo. Intenta descargarlo primero si es de Google Drive.",
                    fileType = getFileType(fileName)
                )
            }
            val fileType = getFileType(fileName)
            Log.d("FileAnalysisService", "Tipo de archivo detectado: $fileType")
            val content = when (fileType) {
                FileType.TEXT, FileType.CODE, FileType.SQL, FileType.JSON, FileType.XML -> {
                    Log.d("FileAnalysisService", "Extrayendo contenido de texto")
                    extractTextContent(inputStream)
                }
                FileType.PDF -> {
                    Log.d("FileAnalysisService", "Archivo PDF detectado")
                    "Contenido PDF detectado - Implementar extracción de PDF"
                }
                FileType.WORD -> {
                    Log.d("FileAnalysisService", "Archivo Word detectado")
                    "Contenido Word detectado - Implementar extracción de Word"
                }
                else -> {
                    Log.d("FileAnalysisService", "Tipo de archivo no soportado: $fileType")
                    "Tipo de archivo no soportado para análisis"
                }
            }
            inputStream.close()
            if (content.isBlank()) {
                Log.e("FileAnalysisService", "Contenido extraído está vacío")
                return@withContext FileAnalysisResult(
                    success = false,
                    error = "El archivo está vacío o no se pudo leer su contenido.",
                    fileType = fileType
                )
            }
            Log.d("FileAnalysisService", "Contenido extraído exitosamente (${content.length} caracteres)")
            // Metadata y contenido generados dinámicamente cada vez
            FileAnalysisResult(
                success = true,
                content = content,
                fileType = fileType,
                metadata = generateMetadata(fileName, content, fileType)
            )
        } catch (e: Exception) {
            Log.e("FileAnalysisService", "Error extracting file content: $fileName", e)
            FileAnalysisResult(
                success = false,
                error = "Error: ${e.message}. Si es un archivo de Google Drive, intenta descargarlo localmente primero.",
                fileType = getFileType(fileName)
            )
        }
    }
    
    /**
     * Maneja específicamente archivos de Google Drive
     */
    private suspend fun handleGoogleDriveFile(uri: Uri, fileName: String): FileAnalysisResult = withContext(Dispatchers.IO) {
        try {
            Log.d("FileAnalysisService", "Procesando archivo de Google Drive: $fileName")
            
            // Mensaje especial para guiar al usuario
            val fileType = getFileType(fileName)
            val helpMessage = """
                |Este parece ser un archivo de Google Drive que no se puede acceder directamente.
                |
                |Por favor, sigue estos pasos:
                |1. Abre el archivo en Google Drive
                |2. Descárgalo a tu dispositivo (menú "..." > "Descargar")
                |3. Vuelve a seleccionar el archivo descargado
                |
                |Los archivos de Google Drive requieren permisos especiales o descarga local
                |para ser analizados correctamente.
            """.trimMargin()
            
            // Intentar extraer el contenido directamente como último recurso
            val contentResolver = context.contentResolver
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val content = extractTextContent(inputStream)
                    if (content.isNotBlank()) {
                        Log.d("FileAnalysisService", "Extracción de Google Drive exitosa (${content.length} caracteres)")
                        return@withContext FileAnalysisResult(
                            success = true,
                            content = content,
                            fileType = fileType,
                            metadata = "Archivo de Google Drive: $fileName"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("FileAnalysisService", "Error accediendo al archivo de Google Drive: ${e.message}")
            }
            
            // Si falló, devolver mensaje de ayuda
            return@withContext FileAnalysisResult(
                success = false,
                error = "No se pudo acceder al archivo de Google Drive. Por favor descárgalo primero.",
                fileType = fileType,
                content = helpMessage
            )
            
        } catch (e: Exception) {
            Log.e("FileAnalysisService", "Error procesando archivo de Google Drive: ${e.message}")
            return@withContext FileAnalysisResult(
                success = false,
                error = "Error accediendo al archivo de Google Drive: ${e.message}",
                fileType = getFileType(fileName)
            )
        }
    }

    private fun extractTextContent(inputStream: java.io.InputStream): String {
        return try {
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val content = reader.readText()
            reader.close()
            content
        } catch (e: Exception) {
            Log.e("FileAnalysisService", "Error reading text content", e)
            "Error al leer el contenido del archivo"
        }
    }

    fun getFileType(fileName: String): FileType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt", "md", "readme" -> FileType.TEXT
            "java", "kt", "py", "js", "ts", "cpp", "c", "h", "cs", "php", "rb", "go", "rs" -> FileType.CODE
            "sql" -> FileType.SQL
            "json" -> FileType.JSON
            "xml", "html", "htm" -> FileType.XML
            "pdf" -> FileType.PDF
            "doc", "docx" -> FileType.WORD
            "xls", "xlsx" -> FileType.EXCEL
            "ppt", "pptx" -> FileType.POWERPOINT
            "jpg", "jpeg", "png", "gif", "bmp" -> FileType.IMAGE
            "mp4", "avi", "mov", "wmv" -> FileType.VIDEO
            "mp3", "wav", "flac" -> FileType.AUDIO
            else -> FileType.UNKNOWN
        }
    }

    private fun generateMetadata(fileName: String, content: String, fileType: FileType): String {
        val metadata = mutableMapOf<String, Any>()
        
        metadata["fileName"] = fileName
        metadata["fileType"] = fileType.name
        metadata["contentLength"] = content.length
        metadata["lineCount"] = content.lines().size
        
        when (fileType) {
            FileType.CODE -> {
                metadata["language"] = detectProgrammingLanguage(fileName, content)
                metadata["hasClasses"] = content.contains("class ", ignoreCase = true)
                metadata["hasFunctions"] = content.contains(Regex("(function|def|public|private|protected)\\s+\\w+\\s*\\("))
                metadata["hasImports"] = content.contains(Regex("(import|include|using|require)\\s+"))
            }
            FileType.SQL -> {
                metadata["hasSelect"] = content.contains("SELECT", ignoreCase = true)
                metadata["hasInsert"] = content.contains("INSERT", ignoreCase = true)
                metadata["hasUpdate"] = content.contains("UPDATE", ignoreCase = true)
                metadata["hasDelete"] = content.contains("DELETE", ignoreCase = true)
                metadata["hasCreateTable"] = content.contains("CREATE TABLE", ignoreCase = true)
            }
            FileType.JSON -> {
                metadata["isValidJson"] = try { 
                    // Aquí podrías usar una librería JSON para validar
                    content.trim().startsWith("{") && content.trim().endsWith("}")
                } catch (e: Exception) { false }
            }
            else -> { /* No metadata específica para otros tipos */ }
        }
        
        return metadata.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }

    private fun detectProgrammingLanguage(fileName: String, content: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        
        return when (extension) {
            "java" -> "Java"
            "kt" -> "Kotlin"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "cpp", "cc" -> "C++"
            "c" -> "C"
            "cs" -> "C#"
            "php" -> "PHP"
            "rb" -> "Ruby"
            "go" -> "Go"
            "rs" -> "Rust"
            else -> {
                // Detectar por contenido si no hay extensión clara
                when {
                    content.contains("package ") && content.contains("class ") -> "Java/Kotlin"
                    content.contains("def ") && content.contains("import ") -> "Python"
                    content.contains("function ") || content.contains("const ") -> "JavaScript"
                    content.contains("#include") -> "C/C++"
                    else -> "Desconocido"
                }
            }
        }
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            Log.d("FileAnalysisService", "Verificando accesibilidad del URI: $uri")
            
            // Verificar si es un URI de Google Drive o similar
            val scheme = uri.scheme
            val authority = uri.authority
            
            Log.d("FileAnalysisService", "Scheme: $scheme, Authority: $authority")
            
            // Verificación especial para Google Drive (com.google.android.apps.docs.storage)
            if (authority?.contains("google") == true || authority?.contains("docs") == true) {
                Log.d("FileAnalysisService", "Detectado URI de Google Drive - Intentando acceso especial")
                try {
                    // Intento directo de abrir el stream
                    context.contentResolver.openInputStream(uri)?.use {
                        Log.d("FileAnalysisService", "Google Drive URI accesible via InputStream")
                        return true
                    }
                } catch (e: Exception) {
                    Log.e("FileAnalysisService", "Error accediendo a URI de Google Drive: ${e.message}")
                    return false
                }
            }
            
            // Si es content://, verificar si podemos acceder
            if (scheme == "content") {
                val contentResolver = context.contentResolver
                try {
                    // Primera verificación: intenta obtener metadatos
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        Log.d("FileAnalysisService", "Cursor obtenido, columnas: ${cursor.columnCount}")
                        if (cursor.count > 0) {
                            return true
                        }
                    }
                    
                    // Segunda verificación: intenta abrir el stream directamente
                    contentResolver.openInputStream(uri)?.use {
                        Log.d("FileAnalysisService", "InputStream abierto exitosamente")
                        return true
                    }
                    
                    Log.w("FileAnalysisService", "No se pudo obtener acceso al contenido del URI")
                    return false
                    
                } catch (e: SecurityException) {
                    Log.e("FileAnalysisService", "Error de seguridad accediendo al URI: ${e.message}")
                    return false
                } catch (e: Exception) {
                    Log.e("FileAnalysisService", "Error accediendo al URI: ${e.message}")
                    return false
                }
            }
            
            // Para otros esquemas (file:// o http://), intentar abrir directamente
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    Log.d("FileAnalysisService", "InputStream abierto exitosamente")
                    return true
                }
            } catch (e: Exception) {
                Log.e("FileAnalysisService", "Error abriendo stream: ${e.message}")
                return false
            }
            
            Log.w("FileAnalysisService", "No se pudo determinar accesibilidad del URI")
            false
            
        } catch (e: Exception) {
            Log.e("FileAnalysisService", "Error verificando accesibilidad del URI: ${e.message}")
            false
        }
    }

    enum class FileType {
        TEXT, CODE, SQL, JSON, XML, PDF, WORD, EXCEL, POWERPOINT, 
        IMAGE, VIDEO, AUDIO, UNKNOWN
    }

    data class FileAnalysisResult(
        val success: Boolean,
        val content: String = "",
        val fileType: FileType,
        val metadata: String = "",
        val error: String? = null
    )
}
