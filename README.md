
# App de Evaluación de Tareas con IA

Esta aplicación Android permite analizar archivos de tareas usando modelos de IA locales a través de Ollama.

## Características

- Chatbot con IA local usando Ollama
- Análisis de archivos con detección automática de tipo
- Interfaz limpia y fácil de usar
- Configuración automática de endpoints
- Soporte para múltiples modelos de IA

## Requisitos

- Android 8.0+ (API 26)
- Ollama instalado en el ordenador
- Modelo Gemma3n instalado (`gemma3n:latest`)

## Configuración de Ollama

Para la funcionalidad completa del chat, es necesario configurar Ollama correctamente:

1. Instala Ollama desde [ollama.com](https://ollama.com/)
2. Ejecuta Ollama en modo servidor:
   ```
   # Windows (PowerShell como administrador)
   $env:OLLAMA_HOST = '0.0.0.0:11434'
   ollama serve
   
   # Linux/Mac
   OLLAMA_HOST=0.0.0.0:11434 ollama serve
   ```
3. Descarga el modelo requerido:
   ```
   ollama pull gemma3n:latest
   ```

## Prueba de Conexión

Para verificar que todo está configurado correctamente:

1. Ejecuta el script de prueba incluido:
   ```
   # Windows (PowerShell)
   .\TEST_OLLAMA_CONNECTION.ps1
   
   # Linux/Mac
   chmod +x ./TEST_OLLAMA_CONNECTION.sh
   ./TEST_OLLAMA_CONNECTION.sh
   ```
2. Verifica que el modelo Gemma3n esté instalado
3. Asegúrate de que Ollama está aceptando conexiones externas (0.0.0.0)

Para solucionar problemas comunes, consulta el archivo [OLLAMA_SETUP.md](OLLAMA_SETUP.md).


## Arquitectura: RAG y MCP en la App

Esta aplicación implementa dos enfoques avanzados de integración de IA para el análisis y consulta de tareas: **RAG** (Retrieval-Augmented Generation) y **MCP** (Model Context Protocol). Cada uno tiene su propio chat y flujo de contexto:


### 1. Chat con RAG (Node.js + Gemma3n)

El chat principal utiliza un microservicio Node.js que implementa la arquitectura RAG. El flujo es el siguiente: 

1. **Documentos adicionales** (archivos de tareas, descripciones, etc.) se codifican usando un modelo de embeddings.
2. Los vectores resultantes se indexan en una **base de datos vectorial**.
3. Cuando el usuario envía una consulta, esta también se codifica.
4. Se realiza una **búsqueda de similitud** en la base vectorial para encontrar los documentos más relevantes.
5. Los documentos similares se recuperan y se combinan con la consulta del usuario.
6. Se construye un **prompt enriquecido** que se envía al modelo LLM (en este caso, `gemma3n:latest` a través de Ollama).
7. El modelo genera una respuesta basada en el contexto recuperado y la consulta.

Este flujo permite respuestas más precisas y contextuales, ya que el modelo tiene acceso a información relevante almacenada previamente. El microservicio Node.js se encarga de la gestión de contexto, la comunicación con Ollama y la orquestación del proceso RAG.

**Ventajas del RAG:**
- Permite respuestas fundamentadas en documentos reales.
- Escalable para grandes volúmenes de información.
- El microservicio puede adaptarse fácilmente a nuevos modelos o fuentes de datos.

### 2. Chat con MCP (Modelo Llama 3)

El segundo chat implementa la infraestructura **MCP (Model Context Protocol)**, que está orientada a la integración profunda entre el modelo LLM y la base de datos de la aplicación. El flujo es:

1. El usuario realiza una consulta o acción desde el chat MCP.
2. El sistema MCP recopila el **contexto estructurado** directamente desde la base de datos local (Room/SQLite), incluyendo usuarios, tareas, tópicos, videos, suscripciones, etc.
3. Se construye un prompt que incluye tanto la consulta del usuario como el contexto relevante de la base de datos.
4. Este prompt se envía al modelo Llama 3 (local, usando llama.cpp u Ollama).
5. El modelo responde considerando tanto la pregunta como el estado actual de la base de datos.

**¿Por qué es un MCP?**
- MCP (Model Context Protocol) es un patrón donde el modelo LLM no solo recibe texto plano, sino que se le proporciona un contexto estructurado y actualizado directamente desde la base de datos de la aplicación.
- Permite consultas complejas, generación de reportes, análisis de relaciones y recomendaciones personalizadas, ya que el modelo tiene acceso a la estructura y datos reales de la app.
- La comunicación entre el MCP y la base de datos es directa, usando DAOs y servicios Kotlin para extraer y resumir la información relevante antes de enviarla al modelo.

**Diferencias clave entre RAG y MCP:**
- RAG utiliza una base vectorial y recuperación de documentos, ideal para análisis de archivos y contexto textual.
- MCP utiliza contexto estructurado de la base de datos, ideal para consultas relacionales y análisis de datos internos.
- Ambos chats están disponibles en la app y pueden usarse según el tipo de consulta o análisis requerido.

---

## Desarrollo

El proyecto está estructurado en:

- `service/`: Servicios de conexión con Ollama, análisis de archivos, integración MCP y RAG
- `ui/`: Fragmentos y actividades para la interfaz de usuario (incluyendo ambos chats)
- `data/`: Entidades, DAOs y modelos de datos

## Solución de Problemas

Si encuentras problemas de conexión:

1. Verifica que Ollama esté ejecutándose con `$env:OLLAMA_HOST = '0.0.0.0:11434'`
2. Asegúrate de que el firewall permite conexiones al puerto 11434
3. Revisa los logs en Android Studio para más detalles

## Licencia

Este proyecto es de código abierto bajo la licencia MIT.
