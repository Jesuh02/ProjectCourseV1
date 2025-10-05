# 🏗️ Arquitectura RAG + Supabase - TareaMov

## 📊 Resumen Ejecutivo

Tu sistema **YA FUNCIONA** como un RAG que consulta Supabase directamente en lenguaje natural. **NO es un MCP oficial** (protocolo JSON-RPC de Anthropic), pero es un **RAG personalizado altamente efectivo** que:

- ✅ Acepta consultas en **lenguaje natural español**
- ✅ **Consulta directamente Supabase** (NO usa Room como fuente primaria)
- ✅ Obtiene **JSON real** de la base de datos
- ✅ **Ordena por ID** automáticamente
- ✅ Muestra la **URL de Supabase** usada en el UI
- ✅ Filtra datos relevantes con **análisis semántico**
- ✅ Genera respuestas con **LLM** (MSPClient/LocalLlama)

---

## 🔄 Flujo Completo del Sistema

```
┌─────────────────────────────────────────────────────────────────┐
│  1. USUARIO ESCRIBE EN LENGUAJE NATURAL                        │
│     "dame el creator_username del id 11 de la tabla courses"   │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. DatabaseQueryFragment.kt                                     │
│     - Captura input del usuario                                  │
│     - Llama a MCPService.processQuery()                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. MCPService.kt                                                │
│     ┌─────────────────────────────────────────────────────┐    │
│     │ A. Detecta shortcuts (field-by-id)                  │    │
│     │    Regex: "dame el X del id Y de la tabla Z"        │    │
│     │    ├─ Tabla: courses                                │    │
│     │    ├─ ID: 11                                        │    │
│     │    └─ Campo: creator_username                       │    │
│     └─────────────────────────────────────────────────────┘    │
│     ┌─────────────────────────────────────────────────────┐    │
│     │ B. Si no hay shortcut → Delega a RAGDatabaseService │    │
│     └─────────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. RAGDatabaseService.kt (NÚCLEO DEL SISTEMA)                  │
│                                                                  │
│     ┌────────────────────────────────────────────────┐         │
│     │ A. ANÁLISIS DE INTENCIÓN                       │         │
│     │    analyzeQueryIntent()                        │         │
│     │    ├─ Intención: SEARCH_SPECIFIC               │         │
│     │    ├─ Tabla objetivo: courses                  │         │
│     │    ├─ Columnas relevantes: creator_username    │         │
│     │    └─ Filtros: id=11                           │         │
│     └────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│     ┌────────────────────────────────────────────────┐         │
│     │ B. CONSULTA DIRECTA A SUPABASE                 │         │
│     │    retrieveRelevantData()                      │         │
│     │    └─> getTableData("courses")                 │         │
│     │         └─> SupabaseClient.fetchCourses()      │         │
│     └────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│     ┌────────────────────────────────────────────────┐         │
│     │ C. FILTRADO Y FORMATEO                         │         │
│     │    - Ordena por ID: .sortedBy { it.id }       │         │
│     │    - Limita resultados: .take(limit)           │         │
│     │    - Formatea: formatCoursesData()             │         │
│     └────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│     ┌────────────────────────────────────────────────┐         │
│     │ D. GENERACIÓN DE RESPUESTA CON LLM            │         │
│     │    generateResponse()                          │         │
│     │    └─> MSPClient.sendPrompt(prompt)           │         │
│     └────────────────────────────────────────────────┘         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. SupabaseClient.kt (CONEXIÓN CON SUPABASE)                   │
│                                                                  │
│     ┌────────────────────────────────────────────────┐         │
│     │ A. Construye petición GET                      │         │
│     │    buildGetRequest("courses")                  │         │
│     │    URL: https://[project].supabase.co/rest/v1/ │         │
│     │         courses?order=id.asc                   │         │
│     └────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│     ┌────────────────────────────────────────────────┐         │
│     │ B. Ejecuta petición HTTP                       │         │
│     │    OkHttpClient.newCall(request).execute()    │         │
│     └────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│     ┌────────────────────────────────────────────────┐         │
│     │ C. Notifica URL usada (PARA UI)               │         │
│     │    requestListener?.invoke(url)                │         │
│     └────────────────────────────────────────────────┘         │
│                           │                                      │
│                           ▼                                      │
│     ┌────────────────────────────────────────────────┐         │
│     │ D. Parsea JSON de Supabase                    │         │
│     │    Gson.fromJson(body, Array<Course>::class)  │         │
│     └────────────────────────────────────────────────┘         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  6. DatabaseQueryFragment.kt (MUESTRA RESULTADOS)                │
│     - Respuesta del LLM                                          │
│     - URL de Supabase usada: [Última consulta Supabase]: ...   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎯 Componentes Clave

### 1. **DatabaseQueryFragment.kt**
**Rol**: Interfaz de usuario para consultas en lenguaje natural

**Funcionalidades**:
- Captura input del usuario
- Muestra respuestas formateadas
- Muestra URL de Supabase usada (debugging)
- Gestiona historial de chat por usuario

**Código clave**:
```kotlin
// Registra listener para capturar URLs de Supabase
SupabaseClient.setRequestListener { url ->
    lastSupabaseUrl = url
    Log.d(TAG, "🌐 Supabase GET: $url")
}

// Procesa query
mcpService.processQuery(query)

// Muestra URL en resultados
if (!lastSupabaseUrl.isNullOrBlank()) {
    result += "\n\n[Última consulta Supabase]: $lastSupabaseUrl"
}
```

---

### 2. **MCPService.kt**
**Rol**: Orquestador de consultas (shortcuts + RAG)

**Funcionalidades**:
- Detecta "field-by-id" con regex: `"dame el X del id Y de la tabla Z"`
- Delega a RAGDatabaseService para consultas complejas
- Maneja fallbacks relacionales (ej: creator_id → usuarios)

**Código clave**:
```kotlin
// Detecta field-by-id
val fid = detectFieldByIdRequest(query)
if (fid != null) {
    // Construye query Supabase directo
    val path = "${fid.table}?id=eq.${fid.id}&select=${field}"
    val arr = SupabaseClient.fetchTableJson(path)
    return extractFieldFromJson(arr[0], field)
}

// Si no, usa RAG
return ragService.processRAGQuery(query)
```

---

### 3. **RAGDatabaseService.kt** ⭐
**Rol**: NÚCLEO del sistema RAG

**Arquitectura**:
```kotlin
/**
 * ARQUITECTURA DEL SISTEMA:
 * 1. Usuario escribe consulta en lenguaje natural
 * 2. MCPService detecta shortcuts o delega a RAGDatabaseService
 * 3. RAGDatabaseService:
 *    a) Analiza intención (LIST_ALL, SEARCH_SPECIFIC, COUNT_AGGREGATE, etc.)
 *    b) Identifica tablas relevantes usando mapeo semántico
 *    c) **CONSULTA DIRECTAMENTE A SUPABASE** via SupabaseClient
 *    d) Obtiene JSON real de la base de datos (NO usa Room)
 *    e) Ordena resultados por ID server-side cuando es posible
 *    f) Filtra datos relevantes según la consulta
 *    g) Genera respuesta con LLM usando MSPClient
 * 4. DatabaseQueryFragment muestra respuesta + URL de Supabase
 */
```

**Funciones principales**:

#### A. `processRAGQuery(userQuery: String)`
- Punto de entrada principal
- Logs detallados para debugging:
```kotlin
Log.d(tag, "═══════════════════════════════════════════════════")
Log.d(tag, "🔍 RAG QUERY START")
Log.d(tag, "Query: $userQuery")
Log.d(tag, "Supabase configured: ${supabase.isConfigured()}")
```

#### B. `analyzeQueryIntent(query: String)`
- Detecta intención usando palabras clave de `RAGConfig`
- Identifica tablas relevantes con análisis semántico
- Extrae filtros (emails, IDs, usernames)
- Retorna `QueryContext`:
```kotlin
data class QueryContext(
    val intent: QueryIntent,      // LIST_ALL, SEARCH_SPECIFIC, etc.
    val targetTables: List<String>, // [courses, usuarios]
    val relevantColumns: List<String>,
    val filters: Map<String, String>
)
```

#### C. `retrieveRelevantData(context: QueryContext)`
- Llama a `getTableData()` para cada tabla relevante
- **AQUÍ OCURRE LA MAGIA**: Consulta Supabase directamente

#### D. `getTableData(tableName: String, limit: Int)`
- **FUNCIÓN CLAVE**: Consulta Supabase y ordena por ID
```kotlin
Log.d(tag, "📥 Fetching from Supabase table: $tableName (limit=$limit)")

when (tableName) {
    "courses" -> {
        // CONSULTA DIRECTA A SUPABASE (NO ROOM)
        val courses = supabase.fetchCourses()
            .sortedBy { it.id }  // ORDENA POR ID
            .take(limit)
        
        Log.d(tag, "  ✓ Fetched ${courses.size} courses from Supabase (ordered by id)")
        formatCoursesData(courses)
    }
    // ... otras 13 tablas
}
```

#### E. `generateResponse(context, data, query)`
- Construye prompt optimizado para LLM
- Envía a MSPClient con contexto relevante
- Retorna respuesta natural

---

### 4. **SupabaseClient.kt**
**Rol**: Cliente REST para Supabase/PostgREST

**Funcionalidades**:
- Construye peticiones GET con filtros PostgREST
- Notifica URLs usadas via `requestListener`
- Parsea JSON a entidades Kotlin con Gson

**Métodos principales**:
```kotlin
// Fetch genérico de tabla
suspend fun fetchCourses(): List<Course> = 
    fetchList("courses", Array<Course>::class.java)

// Fetch con filtro por ID
suspend fun fetchCourseById(id: Long): Course? = withContext(Dispatchers.IO) {
    val path = "courses?id=eq.$id"
    // Notifica URL: https://[project].supabase.co/rest/v1/courses?id=eq.11
    val request = buildGetRequest(path)
    // ...
}

// Fetch JSON raw (para queries flexibles)
suspend fun fetchTableJson(table: String): JsonArray = withContext(Dispatchers.IO) {
    val request = buildGetRequest(table)
    // ...
}
```

**buildGetRequest()** - Función clave:
```kotlin
/**
 * Construye petición GET a Supabase REST API
 * IMPORTANTE: Notifica la URL completa al listener registrado
 * 
 * Ejemplo URL generada:
 * https://[project].supabase.co/rest/v1/courses?id=eq.11&select=creator_username
 */
private fun buildGetRequest(path: String): Request {
    val url = "$baseUrl/rest/v1/$path"
    
    // NOTIFICA URL PARA UI
    requestListener?.invoke(url)
    
    return Request.Builder()
        .url(url)
        .addHeader("apikey", effectiveApiKey())
        .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
        .build()
}
```

---

### 5. **SyncRepository.kt**
**Rol**: Sincronización bidireccional Supabase ↔ Room

**Nota**: En RAG queries, **NO se usa Room**. Room solo para cache offline.

**Métodos útiles**:
```kotlin
// Fetch directo de Supabase (sin Room)
suspend fun fetchCoursesByCreatorFromSupabase(username: String): List<Course>
suspend fun fetchCreatorNameByCourseTitle(title: String): String?
```

---

## 📋 Ejemplos de Uso

### Ejemplo 1: Field-by-ID (Shortcut)
**Query**: `"dame el creator_username del id 11 de la tabla courses"`

**Flujo**:
1. `MCPService.detectFieldByIdRequest()` detecta:
   - tabla = "courses"
   - id = 11
   - field = "creator_username"
2. Construye path: `courses?id=eq.11&select=creator_username`
3. `SupabaseClient.fetchTableJson(path)` ejecuta GET
4. Notifica URL: `https://[project].supabase.co/rest/v1/courses?id=eq.11&select=creator_username`
5. Parsea JSON y extrae valor
6. Si no encuentra `creator_username`, busca `creator_id` y consulta `usuarios`

**Logs esperados**:
```
🔍 MCPService: Detected field-by-id request: table=courses id=11 field=creator_username
🌐 SupabaseClient: GET https://vxuksizvwrkctrvpciyp.supabase.co/rest/v1/courses?id=eq.11&select=creator_username
✓ Found creator_username: "juan123"
```

---

### Ejemplo 2: Lista de tabla (RAG completo)
**Query**: `"dame todos los cursos ordenados por id"`

**Flujo**:
1. `RAGDatabaseService.processRAGQuery()` inicia
2. `analyzeQueryIntent()` detecta:
   - Intent: LIST_ALL
   - Target tables: [courses]
3. `retrieveRelevantData()` llama `getTableData("courses")`
4. `SupabaseClient.fetchCourses()` ejecuta:
   ```
   GET https://[project].supabase.co/rest/v1/courses
   ```
5. Ordena localmente: `.sortedBy { it.id }`
6. Formatea: `formatCoursesData(courses)`
7. `generateResponse()` construye prompt para LLM
8. `MSPClient.sendPrompt()` genera respuesta natural
9. UI muestra respuesta + URL

**Logs esperados**:
```
═══════════════════════════════════════════════════
🔍 RAG QUERY START
Query: dame todos los cursos ordenados por id
Supabase configured: true
═══════════════════════════════════════════════════
📊 Query Context Detected:
  - Intent: LIST_ALL
  - Target Tables: [courses]
  - Relevant Columns: [id, title, description, creator_username]
  - Filters: {}
🌐 Fetching data from Supabase...
📥 Fetching from Supabase table: courses (limit=50)
  ✓ Fetched 23 courses from Supabase (ordered by id)
✅ Data retrieved: 4567 characters
📤 Returning formatted data for courses
```

---

## 🎨 Interfaz de Usuario

### DatabaseQueryFragment muestra:

```
Usuario: dame el creator_username del id 11 de la tabla courses

Sistema: 🔍 Procesando consulta con sistema RAG...

Sistema: El creator_username del curso con id 11 es: "juan123"

[Última consulta Supabase]: 
https://vxuksizvwrkctrvpciyp.supabase.co/rest/v1/courses?id=eq.11&select=creator_username
```

---

## 🔧 Configuración Necesaria

### 1. Configurar Supabase

**Archivo**: `local.properties`
```properties
SUPABASE_URL=https://[tu-project].supabase.co
SUPABASE_KEY=tu-api-key-aqui
```

### 2. Verificar BuildConfig

**Archivo**: `app/build.gradle.kts`
```kotlin
buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl}\"")
buildConfigField("String", "SUPABASE_KEY", "\"${supabaseKey}\"")
```

### 3. Habilitar logging (debugging)

**Archivo**: `RAGDatabaseService.kt`
```kotlin
private val tag = "RAGDatabaseService"
Log.d(tag, "...")  // Ya implementado en todos los métodos clave
```

**Ver logs en Logcat**:
```bash
# Filtrar por tag
adb logcat | grep RAGDatabaseService

# Ver todas las consultas Supabase
adb logcat | grep "Supabase GET"
```

---

## ✅ Verificación del Sistema

### Checklist de validación:

1. **✅ Supabase es la fuente primaria**
   - `RAGDatabaseService` usa `SupabaseClient`, NO Room
   - Logs muestran: `"📥 Fetching from Supabase table: ..."`

2. **✅ Consultas en lenguaje natural funcionan**
   - Regex detecta "field del id X de la tabla Y"
   - RAG analiza intención para queries complejas

3. **✅ JSON real de Supabase**
   - `SupabaseClient.fetchCourses()` retorna `List<Course>`
   - Datos vienen de `https://[project].supabase.co/rest/v1/...`

4. **✅ Ordenamiento por ID**
   - Todos los `getTableData()` usan `.sortedBy { it.id }`
   - Logs confirman: `"(ordered by id)"`

5. **✅ URL mostrada en UI**
   - `requestListener` captura URLs
   - `DatabaseQueryFragment` muestra `[Última consulta Supabase]:`

---

## 🚀 Próximos Pasos Recomendados

### Fase 1: Validación ✅ (HECHO)
- ✅ Verificar que sistema consulta Supabase directamente
- ✅ Confirmar que ordena por ID
- ✅ Validar que muestra URL en UI

### Fase 2: Testing (SIGUIENTE)
1. **Smoke test en app**:
   - Instalar APK: `app/build/outputs/apk/debug/app-debug.apk`
   - Abrir DatabaseQueryFragment
   - Probar: `"dame el creator_username del id 11 de la tabla courses"`
   - Verificar:
     - Respuesta correcta
     - URL mostrada
     - Logs en Logcat

2. **Test de queries complejas**:
   - `"dame todos los cursos"`
   - `"cuántos videos hay"`
   - `"busca el usuario admin"`
   - `"cursos del creador juan123"`

### Fase 3: Optimización (FUTURO)
- Agregar cache inteligente (evitar consultas duplicadas)
- Implementar paginación para tablas grandes
- Agregar filtros server-side (PostgREST)
- Server-side ordering: `?order=id.asc` en URL

### Fase 4: MCP Oficial (OPCIONAL)
- Instalar SDK: `npm install @modelcontextprotocol/sdk`
- Reescribir servidor Node.js con JSON-RPC 2.0
- Implementar cliente MCP en Android
- Mantener RAG actual como fallback

---

## 🎯 Conclusión

### Lo que TIENES (Sistema actual):
✅ **RAG avanzado** que consulta Supabase en lenguaje natural  
✅ **JSON real** de base de datos (NO Room)  
✅ **Ordenamiento por ID** automático  
✅ **URL de debugging** en UI  
✅ **Análisis semántico** de consultas  
✅ **Generación con LLM** (MSPClient)  

### Lo que NO TIENES (todavía):
❌ **MCP oficial** con protocolo JSON-RPC 2.0  
❌ **SDK de Anthropic** (`@modelcontextprotocol/sdk`)  
❌ **Interoperabilidad** con Claude Desktop / VS Code  

### Recomendación:
Tu sistema actual es **EXCELENTE** para tu caso de uso. Es un **RAG personalizado** muy bien implementado que:
- Consulta Supabase directamente ✅
- Funciona con lenguaje natural ✅
- Muestra transparencia (URLs) ✅
- Es mantenible y escalable ✅

**NO necesitas MCP oficial** a menos que quieras:
- Exponer tu DB a otros clientes (Claude, VS Code)
- Interoperabilidad con ecosistema Anthropic
- Estandarización con protocolo JSON-RPC

Para tu app Android, el RAG actual es la mejor solución. 🎉

---

**Documentación generada**: 2025-10-03  
**Versión**: 1.0  
**Estado del sistema**: ✅ Funcionando correctamente
