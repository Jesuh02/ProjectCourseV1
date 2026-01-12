<div align="center">

# CourseV AI

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![DeepSeek](https://img.shields.io/badge/AI-DeepSeek_V3.2-blue?style=for-the-badge)](https://deepseek.com)
[![Cloud](https://img.shields.io/badge/Backend-Cloud_Native-blueviolet?style=for-the-badge)](https://railway.app)

[Comenzar](#-comenzar) • [Zoológico de Agentes](#-zoológico-de-agentes) • [Arquitectura](#-arquitectura) • [Documentación](#-documentación)

<img src="https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/descarga.png" alt="Banner CourseV" width="100%" />

</div>

## 🔥 Novedades

- **[12-01-2026]**: **Integración DeepSeek-V3.2**: Hemos actualizado nuestro motor de razonamiento principal a DeepSeek-V3.2, permitiendo una generación de SQL y lógica de alta precisión.
- **[10-01-2026]**: **Generación de Preguntas Únicas**: El módulo de Aprendizaje por Refuerzo ahora garantiza preguntas 100% únicas mediante el seguimiento de hashes del historial del usuario.
- **[05-01-2026]**: **SQL Autocorrectivo**: El Agente de BI ahora puede detectar errores de SQL y corregirlos autónomamente sin intervención del usuario.

## Introducción

**CourseV AI** es una plataforma educativa de última generación que cierra la brecha entre la **IA Local** (privacidad primero) y la **IA en la Nube** (alto razonamiento). A diferencia de las aplicaciones LMS tradicionales, CourseV se ejecuta en una **Arquitectura Totalmente Agentiva**.

La aplicación se conecta a un **Backend MCP en Node.js** basado en la nube (`index.js`) que orquesta las interacciones entre la base de datos Supabase y el Modelo de Lenguaje Grande **DeepSeek-V3.2**.

### Características Clave

#### 🧠 Aprendizaje por Refuerzo Adaptativo
*Implementado en `ReinforcementLearningFragment.kt`*

Esta característica analiza el tema específico del curso y el contexto de la tarea para generar evaluaciones que se adaptan al progreso del usuario.

- **Lógica**: Genera **10 Preguntas de Evaluación** adaptadas al contenido.
- **Cero Duplicación**: El backend (`index.js`) mantiene un historial de hashes de cada pregunta realizada. Si el LLM genera una pregunta similar, es rechazada y regenerada. Las siguientes 10 preguntas están garantizadas para ser completamente diferentes de las 10 anteriores.

#### 📝 Agente de Calificación Basado en la Nube
*Ubicado en `ChatBotFragment.kt`*

Este agente actúa como un asistente personal de enseñanza capaz de calificar tareas complejas.

- **Capacidad**: Los usuarios suben archivos (PDF/TXT), y el agente utiliza **RAG (Generación Aumentada por Recuperación)** para calificar la tarea frente a la rúbrica.
- **Inteligencia**: Proporciona retroalimentación, señala errores y sugiere mejoras, todo impulsado por el Backend en la Nube.

#### 📊 Lenguaje Natural a SQL (Inteligencia de Negocios)
*Acceso vía `DatabaseQueryFragment.kt`*

Una potente herramienta de BI que permite a usuarios no técnicos acceder a información de la base de datos en tiempo real utilizando lenguaje natural.

- **Texto a SQL**: Convierte preguntas como *\"¿Cuántos estudiantes reprobaron el curso de Java?\"* en SQL ejecutable.
- **Autocorrección**: Utiliza DeepSeek-V3.2 para analizar errores de SQL (por ejemplo, columnas faltantes) y reescribir la consulta automáticamente hasta que tenga éxito.

## 🎁 Zoológico de Agentes

Empleamos agentes especializados para diferentes dominios dentro de la aplicación:

| Nombre del Agente | Modelo Primario | Archivo Fuente | Función |
|:----------:|:-------------:|:-----------:|:---------|
| **Tutor** | DeepSeek-V3.2 | `ReinforcementLearningFragment.kt` | Genera cuestionarios únicos y no repetitivos. |
| **Evaluador** | DeepSeek/Gemma | `ChatBotFragment.kt` | Califica entregas con retroalimentación detallada. |
| **Analista** | DeepSeek-V3.2 | `DatabaseQueryFragment.kt` | Convierte Inglés/Español a SQL y visualiza datos. |

## 🏗 Arquitectura

### Arquitectura Frontend (Aplicación Móvil Android)

```mermaid
%%{init: {'theme': 'base', 'themeVariables': { 'fontSize': '14px'}}}%%
flowchart LR
    %% Estilos tipo HY-Motion DiT
    classDef blue fill:#9dc3e6,stroke:#6c8ebf,color:#000,stroke-width:2px
    classDef orange fill:#f4b183,stroke:#c65911,color:#000,stroke-width:2px
    classDef green fill:#a9d18e,stroke:#548235,color:#000,stroke-width:2px

    %% ENTRADA
    Input1[Entrada Táctil]:::blue
    Input2[Entrada de Voz STT]:::blue
    Input3[Archivos PDF/TXT]:::blue

    %% PROCESAMIENTO
    Proc1[Optimizador<br/>de Consultas]:::orange
    Proc2[Constructor<br/>de Contexto]:::orange
    Proc3[Rastreador<br/>de Hashes]:::orange

    %% STREAM 1: AGENTE TUTOR
    subgraph Stream1 [" Stream Agente Tutor "]
        direction TB
        S1_1[LayerNorm]:::blue
        S1_2[Scale & Shift]:::orange
        S1_3[Generación<br/>Única]:::blue
        S1_4[Verificación<br/>Hash]:::orange
        S1_5[Gate]:::orange
        S1_1 --> S1_2 --> S1_3 --> S1_4 --> S1_5
    end

    %% STREAM 2: AGENTE ANALISTA
    subgraph Stream2 [" Stream Agente Analista "]
        direction TB
        S2_1[LayerNorm]:::blue
        S2_2[Scale & Shift]:::orange
        S2_3[Texto a SQL]:::blue
        S2_4[Auto-Corrección]:::orange
        S2_5[Gate]:::orange
        S2_1 --> S2_2 --> S2_3 --> S2_4 --> S2_5
    end

    %% STREAM 3: AGENTE EVALUADOR
    subgraph Stream3 [" Stream Agente Evaluador "]
        direction TB
        S3_1[LayerNorm]:::blue
        S3_2[Scale & Shift]:::orange
        S3_3[Búsqueda RAG]:::blue
        S3_4[Calificación]:::orange
        S3_5[Gate]:::orange
        S3_1 --> S3_2 --> S3_3 --> S3_4 --> S3_5
    end

    %% SALIDA
    Output1[Protocolo MCP]:::green
    Output2[Conexión<br/>Supabase]:::green

    %% CONEXIONES
    Input1 --> Proc1
    Input2 --> Proc2
    Input3 --> Proc3

    Proc1 --> S1_1
    Proc2 --> S2_1
    Proc3 --> S3_1

    S1_5 --> Output1
    S2_5 --> Output1
    S2_5 -.-> Output2
    S3_5 --> Output1
```

<div align="center">
<sub><b>CourseV Frontend DiT</b> — Arquitectura basada en 3 Streams Paralelos con Gates de Control</sub>
</div>

---

### Arquitectura Backend (Servidor MCP Node.js)

```mermaid
%%{init: {'theme': 'base', 'themeVariables': { 'fontSize': '14px'}}}%%
flowchart LR
    %% Estilos
    classDef blue fill:#9dc3e6,stroke:#6c8ebf,color:#000,stroke-width:2px
    classDef orange fill:#f4b183,stroke:#c65911,color:#000,stroke-width:2px
    classDef green fill:#a9d18e,stroke:#548235,color:#000,stroke-width:2px
    classDef purple fill:#d5a6e6,stroke:#8e44ad,color:#000,stroke-width:2px

    %% ENTRADA DEL CLIENTE
    Client[📱 Cliente<br/>Android]:::purple
    
    %% CAPA DE ENTRADA
    Router[Router<br/>Express]:::blue
    Middleware[Middleware<br/>Seguridad]:::orange

    %% STREAM DOBLE: MCP + RAG
    subgraph DoubleStream [" Stream Doble: MCP & RAG "]
        direction TB
        
        subgraph MCPPath [Ruta MCP]
            direction TB
            MCP1[Validación]:::blue
            MCP2[MCPService]:::orange
            MCP3[Prompt<br/>Optimizado]:::blue
            MCP4[LLM Service]:::orange
            MCP5[Gate]:::orange
            MCP1 --> MCP2 --> MCP3 --> MCP4 --> MCP5
        end

        subgraph RAGPath [Ruta RAG]
            direction TB
            RAG1[Doc Processor]:::blue
            RAG2[Embedding<br/>Service]:::orange
            RAG3[Vector Store]:::blue
            RAG4[RAG Service]:::orange
            RAG5[Gate]:::orange
            RAG1 --> RAG2 --> RAG3 --> RAG4 --> RAG5
        end
    end

    %% STREAM SIMPLE: BI/SQL
    subgraph SingleStream [" Stream Simple: Analista SQL "]
        direction TB
        SQL1[Parser SQL]:::blue
        SQL2[Validator]:::orange
        SQL3[Supabase<br/>Query]:::blue
        SQL4[Auto-Fix]:::orange
        SQL5[Gate]:::orange
        SQL1 --> SQL2 --> SQL3 --> SQL4 --> SQL5
    end

    %% CAPA DE SALIDA
    Deep[DeepSeek V3.2<br/>☁️ API]:::green
    Supa[Supabase<br/>PostgreSQL]:::green
    Cache[Redis Cache]:::orange

    %% CONEXIONES
    Client ==> Router
    Router --> Middleware
    Middleware --> MCP1
    Middleware --> RAG1
    Middleware --> SQL1

    MCP5 --> Deep
    RAG5 --> Supa
    SQL5 --> Supa
    
    Deep -.-> Cache
    Supa -.-> Cache
```

<div align="center">
<sub><b>CourseV Backend MCP</b> — Motor de IA con Double Stream (MCP+RAG) y Single Stream (SQL)</sub>
</div>

---

El sistema sigue una estricta separación de responsabilidades:
1.  **Frontend (Android)**: Maneja la UI, TTS (Texto a Voz) y recopilación de contexto.
2.  **Backend MCP (Nube)**: Ubicado en `distribucion_de_contexto/`. Maneja el procesamiento de IA de alta carga.
3.  **Base de Datos (Supabase)**: Almacena Historial de Usuario, Embeddings Vectoriales y Datos del Curso.

## 🤗 Comenzar

### 1. Instalación

Clona el repositorio y ábrelo en Android Studio.

```bash
git clone https://github.com/TuRepo/CourseV.git
cd CourseV
```

### 2. Configuración del Backend

El backend es crítico para los agentes de IA.

```bash
cd distribucion_de_contexto/MCP-backendDeploy
npm install
npm start
```

### 3. Construir App Android

```bash
./gradlew assembleDebug
```

## 🔗 Configuración de DeepSeek

CourseV utiliza **DeepSeek-V3.2** por sus capacidades superiores de razonamiento. Asegúrate de que tu archivo `.env` del backend esté configurado:

```env
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxx
MODEL_VERSION=deepseek-chat-v3.2
```

## Agradecimientos

Agradecimiento especial a la comunidad de código abierto y a los equipos detrás de **DeepSeek**, **Ollama** y **Supabase**.

---
<div align="center">
  <sub>Diseñado con precisión. Impulsado por CourseV AI.</sub>
</div>
