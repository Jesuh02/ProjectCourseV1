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

### Arquitectura Frontend (App Móvil)

```mermaid
%%{init: {'theme': 'base', 'themeVariables': { 'primaryColor': '#9dc3e6', 'primaryTextColor': '#000', 'primaryBorderColor': '#6c8ebf', 'lineColor': '#333', 'secondaryColor': '#f4b183', 'tertiaryColor': '#a9d18e'}}}%%
flowchart TB
    %% Estilos tipo HY-Motion DiT
    classDef blue fill:#9dc3e6,stroke:#6c8ebf,color:#000,stroke-width:2px,rx:8,ry:8
    classDef orange fill:#f4b183,stroke:#c65911,color:#000,stroke-width:2px,rx:8,ry:8
    classDef green fill:#a9d18e,stroke:#548235,color:#000,stroke-width:2px,rx:8,ry:8
    classDef yellow fill:#ffd966,stroke:#bf9000,color:#000,stroke-width:2px,rx:8,ry:8

    %% ═══════════════════════════════════════════════════
    %% CAPA DE ENTRADA (Input Layer)
    %% ═══════════════════════════════════════════════════
    subgraph InputLayer [" "]
        direction LR
        Touch[Táctil]:::blue
        Voice[STT / Voz]:::blue
        Files[Archivos]:::blue
    end

    %% ═══════════════════════════════════════════════════
    %% CAPA DE PROCESAMIENTO (Processing Layer)  
    %% ═══════════════════════════════════════════════════
    subgraph ProcessLayer [" "]
        direction LR
        QueryOpt[Query Optimizer]:::orange
        CtxBuild[Context Builder]:::orange
        HashTrack[Hash Tracker]:::orange
    end

    %% ═══════════════════════════════════════════════════
    %% BLOQUES AGENTIVOS (Agent Blocks)
    %% ═══════════════════════════════════════════════════
    subgraph AgentBlocks [" "]
        direction LR
        
        subgraph RL_Stream [Agente Tutor]
            direction TB
            RL1[ViewNorm]:::blue
            RL2[Scale & Shift]:::orange
            RL3[Unique Gen]:::blue
            RL4[Hash Check]:::orange
            RL5[Gate]:::orange
            RL1 --> RL2 --> RL3 --> RL4 --> RL5
        end

        subgraph BI_Stream [Agente Analista]
            direction TB
            BI1[ViewNorm]:::blue
            BI2[Scale & Shift]:::orange
            BI3[Text-to-SQL]:::blue
            BI4[Auto-Correct]:::orange
            BI5[Gate]:::orange
            BI1 --> BI2 --> BI3 --> BI4 --> BI5
        end

        subgraph RAG_Stream [Agente Evaluador]
            direction TB
            RAG1[ViewNorm]:::blue
            RAG2[Scale & Shift]:::orange
            RAG3[RAG Search]:::blue
            RAG4[Scoring]:::orange
            RAG5[Gate]:::orange
            RAG1 --> RAG2 --> RAG3 --> RAG4 --> RAG5
        end
    end

    %% ═══════════════════════════════════════════════════
    %% CAPA DE SALIDA (Output Layer)
    %% ═══════════════════════════════════════════════════
    subgraph OutputLayer [" "]
        direction LR
        MCP[MCP Protocol]:::green
        Supabase[Supabase Conn]:::green
    end

    %% ═══════════════════════════════════════════════════
    %% CONEXIONES PRINCIPALES
    %% ═══════════════════════════════════════════════════
    Touch --> QueryOpt
    Voice --> CtxBuild
    Files --> HashTrack

    QueryOpt --> RL1
    CtxBuild --> BI1
    HashTrack --> RAG1

    RL5 --> MCP
    BI5 --> MCP
    BI5 -.-> Supabase
    RAG5 --> MCP

    %% Salida Final
    MCP ==> Backend[☁️ DeepSeek V3.2]:::yellow
    Supabase ==> DB[(PostgreSQL)]:::yellow
```

<div align="center">
<sub><b>CourseV Frontend DiT</b> — Arquitectura basada en Streams con Gates de Control</sub>
</div>

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
