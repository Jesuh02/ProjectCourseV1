package com.example.tareamov.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.model.FileContent
import com.example.tareamov.data.model.FileSection
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.json.JSONArray
import org.json.JSONObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.*

/**
 * Servicio para convertir diferentes tipos de archivos a JSON estructurado
 * que puede ser procesado por modelos de IA a través del protocolo MCP.
 */
class FileConverterService(private val context: Context) {
    
    companion object {
        private const val TAG = "FileConverterService"
        
        // Tipos de archivos soportados
        private val SUPPORTED_EXTENSIONS = mapOf(
            "json" to "application/json",
            "txt" to "text/plain",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pdf" to "application/pdf",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    }
    
    /**
     * Convierte un archivo a un formato JSON estructurado
     * @param uri URI del archivo a convertir
     * @param fileName Nombre del archivo
     * @return FileContext con el contenido convertido
     */
    suspend fun convertFileToStructuredJson(uri: Uri, fileName: String): FileContext {
        val fileExtension = getFileExtension(fileName).lowercase()
        
        return try {
            Log.d(TAG, "Convirtiendo archivo: $fileName con extensión $fileExtension")
            
            val fileContent = when (fileExtension) {
                "json" -> processJsonFile(uri)
                "txt" -> processTextFile(uri, fileName)
                "doc", "docx" -> processWordFile(uri, fileName)
                "pdf" -> processPdfFile(uri, fileName)
                "ppt", "pptx" -> processPowerPointFile(uri, fileName)
                "xls", "xlsx" -> processExcelFile(uri, fileName)
                else -> {
                    Log.w(TAG, "Tipo de archivo no soportado: $fileExtension")
                    createGenericFileContent(uri, fileName, "Tipo de archivo no soportado: $fileExtension")
                }
            }
            
            // Convertir el FileContent a FileContext para la integración con MCP
            fileContentToFileContext(fileContent)
        } catch (e: Exception) {
            Log.e(TAG, "Error al convertir archivo: ${e.message}", e)
            createErrorFileContext(fileName, "Error al procesar el archivo: ${e.message}")
        }
    }
    
    /**
     * Procesa un archivo JSON
     */
    private fun processJsonFile(uri: Uri): FileContent {
        val inputStream = context.contentResolver.openInputStream(uri)
        val jsonContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
        
        // Intenta formatear y estructurar el JSON
        try {
            val jsonObject = if (jsonContent.trim().startsWith("{")) {
                JSONObject(jsonContent)
            } else if (jsonContent.trim().startsWith("[")) {
                JSONObject().put("items", JSONArray(jsonContent))
            } else {
                JSONObject().put("content", jsonContent)
            }
            
            // Extraer estructura básica del JSON
            val structure = mutableMapOf<String, Any>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = jsonObject.get(key)
                structure[key] = when (value) {
                    is JSONObject -> "object"
                    is JSONArray -> "array[${value.length()}]"
                    else -> value.javaClass.simpleName
                }
            }
            
            return FileContent(
                fileName = uri.lastPathSegment ?: "archivo.json",
                fileType = "json",
                content = jsonContent,
                structure = structure,
                sections = listOf(FileSection(title = "JSON Content", content = jsonContent, type = "json"))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando JSON: ${e.message}", e)
            return FileContent(
                fileName = uri.lastPathSegment ?: "archivo.json",
                fileType = "json",
                content = jsonContent,
                sections = listOf(FileSection(content = jsonContent, type = "text"))
            )
        }
    }
    
    /**
     * Procesa un archivo de texto plano
     */
    private fun processTextFile(uri: Uri, fileName: String): FileContent {
        val inputStream = context.contentResolver.openInputStream(uri)
        val textContent = inputStream?.bufferedReader().use { it?.readText() ?: "" }
        
        // Divide el texto en secciones basadas en líneas vacías o posibles títulos
        val sections = mutableListOf<FileSection>()
        val lines = textContent.split("\n")
        
        var currentSectionTitle = "Inicio del documento"
        var currentSectionContent = StringBuilder()
        var inSection = false
        
        for (line in lines) {
            val trimmedLine = line.trim()
            
            // Detecta posibles títulos (líneas cortas, mayúsculas o con números de sección)
            if (trimmedLine.isNotEmpty() && 
                (trimmedLine.length < 50 && 
                 (trimmedLine.uppercase() == trimmedLine || 
                  trimmedLine.matches(Regex("^\\d+\\..*")) || 
                  trimmedLine.matches(Regex("^[A-Z][a-z]+.*:"))))) {
                
                // Guarda la sección anterior si existe
                if (inSection && currentSectionContent.isNotEmpty()) {
                    sections.add(FileSection(
                        title = currentSectionTitle,
                        content = currentSectionContent.toString().trim(),
                        type = "text"
                    ))
                    currentSectionContent = StringBuilder()
                }
                
                currentSectionTitle = trimmedLine
                inSection = true
            } else if (trimmedLine.isEmpty() && inSection && currentSectionContent.isNotEmpty()) {
                // Final de párrafo, añade un salto de línea
                currentSectionContent.append("\n\n")
            } else {
                // Agrega la línea al contenido de la sección actual
                if (trimmedLine.isNotEmpty() || currentSectionContent.isNotEmpty()) {
                    currentSectionContent.append(line).append("\n")
                    inSection = true
                }
            }
        }
        
        // Añade la última sección si hay contenido
        if (currentSectionContent.isNotEmpty()) {
            sections.add(FileSection(
                title = currentSectionTitle,
                content = currentSectionContent.toString().trim(),
                type = "text"
            ))
        }
        
        // Si no se detectaron secciones, crea una sección única con todo el contenido
        if (sections.isEmpty()) {
            sections.add(FileSection(
                title = "Contenido completo",
                content = textContent,
                type = "text"
            ))
        }
        
        return FileContent(
            fileName = fileName,
            fileType = "txt",
            content = textContent,
            sections = sections
        )
    }
    
    /**
     * Procesa un archivo Word (DOC/DOCX)
     */
    private fun processWordFile(uri: Uri, fileName: String): FileContent {
        val inputStream = context.contentResolver.openInputStream(uri)
        val document = XWPFDocument(inputStream)
        val allText = StringBuilder()
        val sections = mutableListOf<FileSection>()
        
        // Procesa párrafos
        for (paragraph in document.paragraphs) {
            val text = paragraph.text
            allText.append(text).append("\n")
            
            // Detecta si es un título basado en el estilo
            val isTitle = paragraph.style?.contains("Heading") == true || 
                          paragraph.style?.contains("Title") == true ||
                          (text.length < 100 && text.isNotEmpty() && paragraph.runs.any { it.isBold })
            
            if (isTitle) {
                sections.add(FileSection(
                    title = text,
                    content = text,
                    level = when {
                        paragraph.style?.contains("Heading 1") == true -> 1
                        paragraph.style?.contains("Heading 2") == true -> 2
                        paragraph.style?.contains("Heading 3") == true -> 3
                        else -> 0
                    },
                    type = "heading"
                ))
            } else if (text.isNotEmpty()) {
                sections.add(FileSection(
                    content = text,
                    type = "paragraph"
                ))
            }
        }
        
        // Procesa tablas
        document.tables.forEachIndexed { index, table ->
            val tableContent = StringBuilder()
            tableContent.append("Tabla ${index + 1}:\n")
            
            for (row in table.rows) {
                for (cell in row.tableCells) {
                    tableContent.append(cell.text).append("\t")
                }
                tableContent.append("\n")
            }
            
            sections.add(FileSection(
                title = "Tabla ${index + 1}",
                content = tableContent.toString(),
                type = "table"
            ))
            
            allText.append(tableContent).append("\n\n")
        }
        
        document.close()
        inputStream?.close()
        
        return FileContent(
            fileName = fileName,
            fileType = "docx",
            content = allText.toString(),
            metadata = mapOf("pageCount" to (document.document?.body?.sectPr?.toString() ?: "Unknown")),
            sections = sections
        )
    }
    
    /**
     * Procesa un archivo PDF
     */
    private fun processPdfFile(uri: Uri, fileName: String): FileContent {
        val inputStream = context.contentResolver.openInputStream(uri)
        val document = PDDocument.load(inputStream)
        val stripper = PDFTextStripper()
        
        // Configurar para extraer texto por páginas
        val allText = StringBuilder()
        val sections = mutableListOf<FileSection>()
        
        // Extrae texto por páginas
        for (i in 0 until document.numberOfPages) {
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            val pageText = stripper.getText(document)
            
            sections.add(FileSection(
                title = "Página ${i + 1}",
                content = pageText,
                type = "page"
            ))
            
            allText.append(pageText).append("\n\n")
        }
        
        // Información del documento
        val metadata = mutableMapOf<String, Any>()
        metadata["pageCount"] = document.numberOfPages
        
        if (document.documentInformation != null) {
            document.documentInformation.title?.let { metadata["title"] = it }
            document.documentInformation.author?.let { metadata["author"] = it }
            document.documentInformation.subject?.let { metadata["subject"] = it }
            document.documentInformation.keywords?.let { metadata["keywords"] = it }
            document.documentInformation.creator?.let { metadata["creator"] = it }
            document.documentInformation.producer?.let { metadata["producer"] = it }
        }
        
        document.close()
        inputStream?.close()
        
        return FileContent(
            fileName = fileName,
            fileType = "pdf",
            content = allText.toString(),
            metadata = metadata,
            sections = sections
        )
    }
    
    /**
     * Procesa un archivo PowerPoint (PPT/PPTX)
     */
    private fun processPowerPointFile(uri: Uri, fileName: String): FileContent {
        val inputStream = context.contentResolver.openInputStream(uri)
        val presentation = XMLSlideShow(inputStream)
        val allText = StringBuilder()
        val sections = mutableListOf<FileSection>()
        
        // Procesa diapositivas
        presentation.slides.forEachIndexed { index, slide ->
            val slideContent = StringBuilder()
            slideContent.append("Diapositiva ${index + 1}:\n")
            
            // Extrae título si existe
            var slideTitle = "Diapositiva ${index + 1}"
            
            // Extrae texto de cada forma en la diapositiva
            for (shape in slide.shapes) {
                // Check if the shape has text
                if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                    for (paragraph in shape.textParagraphs) {
                        val text = paragraph.text
                        slideContent.append(text).append("\n")
                    }
                }
            }
            
            sections.add(FileSection(
                title = slideTitle,
                content = slideContent.toString(),
                type = "slide"
            ))
            
            allText.append(slideContent).append("\n\n")
        }
        
        // Información del documento
        val metadata = mutableMapOf<String, Any>()
        metadata["slideCount"] = presentation.slides.size
        
        val docProps = presentation.properties
        if (docProps != null) {
            docProps.coreProperties?.let { core ->
                core.title?.let { metadata["title"] = it }
                core.creator?.let { metadata["author"] = it }
                core.subject?.let { metadata["subject"] = it }
                core.keywords?.let { metadata["keywords"] = it }
            }
        }
        
        presentation.close()
        inputStream?.close()
        
        return FileContent(
            fileName = fileName,
            fileType = "pptx",
            content = allText.toString(),
            metadata = metadata,
            sections = sections
        )
    }
    
    /**
     * Procesa un archivo Excel (XLS/XLSX)
     */
    private fun processExcelFile(uri: Uri, fileName: String): FileContent {
        val inputStream = context.contentResolver.openInputStream(uri)
        val workbook = WorkbookFactory.create(inputStream)
        val allText = StringBuilder()
        val sections = mutableListOf<FileSection>()
        
        // Procesa hojas
        for (i in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(i)
            val sheetName = workbook.getSheetName(i)
            val sheetContent = StringBuilder()
            
            sheetContent.append("Hoja: $sheetName\n")
            
            // Procesa filas
            for (row in sheet) {
                for (cell in row) {
                    val cellValue = when (cell.cellType) {
                        org.apache.poi.ss.usermodel.CellType.NUMERIC -> cell.numericCellValue.toString()
                        org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
                        org.apache.poi.ss.usermodel.CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        org.apache.poi.ss.usermodel.CellType.FORMULA -> try {
                            cell.stringCellValue
                        } catch (e: Exception) {
                            try {
                                cell.numericCellValue.toString()
                            } catch (e2: Exception) {
                                "FORMULA"
                            }
                        }
                        else -> ""
                    }
                    
                    sheetContent.append(cellValue).append("\t")
                }
                sheetContent.append("\n")
            }
            
            sections.add(FileSection(
                title = sheetName,
                content = sheetContent.toString(),
                type = "spreadsheet"
            ))
            
            allText.append(sheetContent).append("\n\n")
        }
        
        workbook.close()
        inputStream?.close()
        
        return FileContent(
            fileName = fileName,
            fileType = "xlsx",
            content = allText.toString(),
            metadata = mapOf("sheetCount" to workbook.numberOfSheets),
            sections = sections
        )
    }
    
    /**
     * Convierte un FileContent a FileContext para integración con MCP
     */
    private fun fileContentToFileContext(fileContent: FileContent): FileContext {
        // Construye un JSON estructurado para el MCP
        val jsonBuilder = JSONObject()
        
        // Añade metadatos básicos
        jsonBuilder.put("fileName", fileContent.fileName)
        jsonBuilder.put("fileType", fileContent.fileType)
        
        // Añade metadatos adicionales
        val metadataJson = JSONObject()
        fileContent.metadata.forEach { (key, value) -> metadataJson.put(key, value) }
        jsonBuilder.put("metadata", metadataJson)
        
        // Añade estructura si existe
        if (fileContent.structure.isNotEmpty()) {
            val structureJson = JSONObject()
            fileContent.structure.forEach { (key, value) -> structureJson.put(key, value) }
            jsonBuilder.put("structure", structureJson)
        }
        
        // Añade secciones
        val sectionsArray = JSONArray()
        fileContent.sections.forEach { section ->
            val sectionJson = JSONObject()
            section.title?.let { sectionJson.put("title", it) }
            sectionJson.put("content", section.content)
            sectionJson.put("type", section.type)
            sectionJson.put("level", section.level)
            
            // Añade metadatos de sección si existen
            if (section.metadata.isNotEmpty()) {
                val sectionMetadataJson = JSONObject()
                section.metadata.forEach { (key, value) -> sectionMetadataJson.put(key, value) }
                sectionJson.put("metadata", sectionMetadataJson)
            }
            
            sectionsArray.put(sectionJson)
        }
        jsonBuilder.put("sections", sectionsArray)
        
        // Añade contenido completo
        jsonBuilder.put("fullContent", fileContent.content)
        
        return FileContext(
            submissionId = 0, // Placeholder, should be set by caller
            fileName = fileContent.fileName,
            fileType = fileContent.fileType,
            fileContent = fileContent.content,
            jsonContent = jsonBuilder.toString(),
            contentSummary = generateSummary(fileContent)
        )
    }
    
    /**
     * Genera un resumen del contenido del archivo
     */
    private fun generateSummary(fileContent: FileContent): String {
        val summary = StringBuilder()
        summary.append("Archivo: ${fileContent.fileName} (${fileContent.fileType.uppercase()})\n")
        
        // Añade información de metadatos
        if (fileContent.metadata.isNotEmpty()) {
            summary.append("Metadatos: ")
            fileContent.metadata.entries.take(3).forEach { (key, value) ->
                summary.append("$key: $value, ")
            }
            summary.append("\n")
        }
        
        // Añade resumen de secciones
        summary.append("Contiene ${fileContent.sections.size} secciones.\n")
        
        // Añade las primeras líneas de contenido como vista previa
        val previewContent = fileContent.content.take(200).replace("\n", " ")
        summary.append("Vista previa: $previewContent...")
        
        return summary.toString()
    }
    
    /**
     * Crea un FileContent genérico para archivos no procesables
     */
    private fun createGenericFileContent(uri: Uri, fileName: String, message: String): FileContent {
        return FileContent(
            fileName = fileName,
            fileType = getFileExtension(fileName),
            content = message,
            sections = listOf(FileSection(
                title = "Información",
                content = message,
                type = "message"
            ))
        )
    }
    
    /**
     * Crea un FileContext de error
     */
    private fun createErrorFileContext(fileName: String, errorMessage: String): FileContext {
        val jsonObject = JSONObject()
        jsonObject.put("error", true)
        jsonObject.put("message", errorMessage)
        
        return FileContext(
            submissionId = 0, // Placeholder, should be set by caller
            fileName = fileName,
            fileType = getFileExtension(fileName),
            fileContent = errorMessage,
            jsonContent = jsonObject.toString(),
            contentSummary = "Error al procesar el archivo: $errorMessage"
        )
    }
    
    /**
     * Obtiene la extensión de un archivo
     */
    private fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "")
    }
}
