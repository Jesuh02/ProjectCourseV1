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

El sistema sigue una estricta separación de responsabilidades:
1.  **Frontend (Android)**: Maneja la UI, TTS (Texto a Voz) y recopilación de contexto.
2.  **Backend MCP (Nube)**: Ubicado en `distribucion_de_contexto/`. Maneja el procesamiento de IA de alta carga.
3.  **Base de Datos (Supabase)**: Almacena Historial de Usuario, Embeddings Vectoriales y Datos del Curso.

---

### 🗺️ Diagrama Interactivo Navegable (Estilo Lucidchart)

<div align="center">
  
**Haz clic en la imagen para explorar el diagrama completo de la arquitectura de forma interactiva** 🔍

[![Arquitectura Completa del Sistema CourseV](https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/arquitectura.png)](https://www.mermaidchart.com/app/projects/fa72bcaf-267c-492e-89e6-1c4b155de335/diagrams/2706f980-5691-4b44-bf04-b3dc8d2b97c1/share/invite/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJkb2N1bWVudElEIjoiMjcwNmY5ODAtNTY5MS00YjQ0LWJmMDQtYjNkYzhkMmI5N2MxIiwiYWNjZXNzIjoiVmlldyIsImlhdCI6MTc2ODIxNTAzNX0.A8F-gSi40JQsLKpyzTAnbwtx0rbXWf5i5xnCQ5xjEqQ)

<sub>📐 **Diagrama Interactivo en MermaidChart** — Navega, amplía y explora cada componente de la arquitectura completa: Frontend Android (Kotlin/MVVM), Backend MCP (Node.js/Clean Architecture), Protocolos de Comunicación y Servicios Externos en la Nube</sub>

</div>

---

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
