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
%%{init: {'theme': 'default', 'themeVariables': { 'background': '#ffffff', 'mainBkg': '#ffffff', 'clusterBkg': '#f5f5f5', 'clusterBorder': '#cccccc', 'primaryColor': '#9dc3e6', 'primaryTextColor': '#000000', 'primaryBorderColor': '#6c8ebf', 'lineColor': '#333333', 'secondaryColor': '#f4b183', 'tertiaryColor': '#a9d18e', 'fontSize': '13px', 'fontFamily': 'arial', 'edgeLabelBackground':'#ffffff', 'textColor': '#000000'}}}%%
flowchart LR
    %% Estilos tipo HY-Motion DiT con fondo claro
    classDef blue fill:#9dc3e6,stroke:#6c8ebf,color:#000,stroke-width:2.5px,rx:5,ry:5
    classDef orange fill:#f4b183,stroke:#c65911,color:#000,stroke-width:2.5px,rx:5,ry:5
    classDef green fill:#a9d18e,stroke:#548235,color:#000,stroke-width:2.5px,rx:5,ry:5

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
        S1_1[NormalizaciónCapa]:::blue
        S1_2[Escalar y Desplazar]:::orange
        S1_3[Generación<br/>Única]:::blue
        S1_4[Verificación<br/>Hash]:::orange
        S1_5[Compuerta]:::orange
        S1_1 --> S1_2 --> S1_3 --> S1_4 --> S1_5
    end

    %% STREAM 2: AGENTE ANALISTA
    subgraph Stream2 [" Stream Agente Analista "]
        direction TB
        S2_1[NormalizaciónCapa]:::blue
        S2_2[Escalar y Desplazar]:::orange
        S2_3[Texto a SQL]:::blue
        S2_4[Auto-Corrección]:::orange
        S2_5[Compuerta]:::orange
        S2_1 --> S2_2 --> S2_3 --> S2_4 --> S2_5
    end

    %% STREAM 3: AGENTE EVALUADOR
    subgraph Stream3 [" Stream Agente Evaluador "]
        direction TB
        S3_1[NormalizaciónCapa]:::blue
        S3_2[Escalar y Desplazar]:::orange
        S3_3[Búsqueda RAG]:::blue
        S3_4[Calificación]:::orange
        S3_5[Compuerta]:::orange
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
<sub><b>CourseV Frontend DiT</b> — Arquitectura basada en 3 Flujos Paralelos con Compuertas de Control</sub>
</div>

---

### Arquitectura Backend (Servidor MCP Node.js)

```mermaid
%%{init: {'theme': 'default', 'themeVariables': { 'background': '#ffffff', 'mainBkg': '#ffffff', 'clusterBkg': '#f5f5f5', 'clusterBorder': '#cccccc', 'primaryColor': '#9dc3e6', 'primaryTextColor': '#000000', 'primaryBorderColor': '#6c8ebf', 'lineColor': '#333333', 'secondaryColor': '#f4b183', 'tertiaryColor': '#a9d18e', 'fontSize': '13px', 'fontFamily': 'arial', 'edgeLabelBackground':'#ffffff', 'textColor': '#000000'}}}%%
flowchart LR
    %% Estilos con fondo claro
    classDef blue fill:#9dc3e6,stroke:#6c8ebf,color:#000,stroke-width:2.5px,rx:5,ry:5
    classDef orange fill:#f4b183,stroke:#c65911,color:#000,stroke-width:2.5px,rx:5,ry:5
    classDef green fill:#a9d18e,stroke:#548235,color:#000,stroke-width:2.5px,rx:5,ry:5
    classDef purple fill:#d5a6e6,stroke:#8e44ad,color:#000,stroke-width:2.5px,rx:5,ry:5

    %% ENTRADA DEL CLIENTE
    Client[📱 Cliente<br/>Android]:::purple
    
    %% CAPA DE ENTRADA
    Router[Enrutador<br/>Express]:::blue
    Middleware[Middleware<br/>Seguridad]:::orange

    %% STREAM DOBLE: MCP + RAG
    subgraph DoubleStream [" Flujo Doble: MCP y RAG "]
        direction TB
        
        subgraph MCPPath [Ruta MCP]
            direction TB
            MCP1[Validación]:::blue
            MCP2[Servicio MCP]:::orange
            MCP3[Prompt<br/>Optimizado]:::blue
            MCP4[Servicio LLM]:::orange
            MCP5[Compuerta]:::orange
            MCP1 --> MCP2 --> MCP3 --> MCP4 --> MCP5
        end

        subgraph RAGPath [Ruta RAG]
            direction TB
            RAG1[Procesador Doc]:::blue
            RAG2[Servicio<br/>Embeddings]:::orange
            RAG3[Almacén Vectorial]:::blue
            RAG4[Servicio RAG]:::orange
            RAG5[Compuerta]:::orange
            RAG1 --> RAG2 --> RAG3 --> RAG4 --> RAG5
        end
    end

    %% STREAM SIMPLE: BI/SQL
    subgraph SingleStream [" Flujo Simple: Analista SQL "]
        direction TB
        SQL1[Analizador SQL]:::blue
        SQL2[Validador]:::orange
        SQL3[Consulta<br/>Supabase]:::blue
        SQL4[Auto-Corrección]:::orange
        SQL5[Compuerta]:::orange
        SQL1 --> SQL2 --> SQL3 --> SQL4 --> SQL5
    end

    %% CAPA DE SALIDA
    Deep[DeepSeek V3.2<br/>☁️ API]:::green
    Supa[Supabase<br/>PostgreSQL]:::green
    Cache[Caché Redis]:::orange

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
<sub><b>CourseV Backend MCP</b> — Motor de IA con Flujo Doble (MCP+RAG) y Flujo Simple (SQL)</sub>
</div>

---

El sistema sigue una estricta separación de responsabilidades:
1.  **Frontend (Android)**: Maneja la UI, TTS (Texto a Voz) y recopilación de contexto.
2.  **Backend MCP (Nube)**: Ubicado en `distribucion_de_contexto/`. Maneja el procesamiento de IA de alta carga.
3.  **Base de Datos (Supabase)**: Almacena Historial de Usuario, Embeddings Vectoriales y Datos del Curso.

---

### 🗺️ Diagrama Interactivo Navegable (Estilo Lucidchart)

<div align="center">
  
**Haz clic en la imagen para explorar el diagrama completo de la arquitectura de forma interactiva** 🔍

[![Arquitectura Completa del Sistema CourseV](https://www.mermaidchart.com/raw/2706f980-5691-4b44-bf04-b3dc8d2b97c1?theme=light&version=v0.1&format=svg)](https://www.mermaidchart.com/app/projects/fa72bcaf-267c-492e-89e6-1c4b155de335/diagrams/2706f980-5691-4b44-bf04-b3dc8d2b97c1/share/invite/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJkb2N1bWVudElEIjoiMjcwNmY5ODAtNTY5MS00YjQ0LWJmMDQtYjNkYzhkMmI5N2MxIiwiYWNjZXNzIjoiVmlldyIsImlhdCI6MTc2ODIxNTAzNX0.A8F-gSi40JQsLKpyzTAnbwtx0rbXWf5i5xnCQ5xjEqQ)

<sub>📐 **Diagrama Interactivo en MermaidChart** — Navega, amplía y explora cada componente de la arquitectura completa</sub>

</div>

---

### Arquitectura Completa del Sistema (Frontend + Backend + Comunicación)

```mermaid
%%{init: {'theme': 'default', 'themeVariables': { 'background': '#ffffff', 'mainBkg': '#ffffff', 'clusterBkg': '#f9f9f9', 'fontSize': '11px'}}}%%
flowchart TB
    %% ═══════════════════════════════════════════════════════════════════
    %% ESTILOS DE COMPONENTES
    %% ═══════════════════════════════════════════════════════════════════
    classDef ui fill:#e3f2fd,stroke:#1976d2,color:#000,stroke-width:2.5px
    classDef vm fill:#f3e5f5,stroke:#7b1fa2,color:#000,stroke-width:2.5px
    classDef repo fill:#fff3e0,stroke:#f57c00,color:#000,stroke-width:2.5px
    classDef network fill:#e1f5fe,stroke:#0277bd,color:#000,stroke-width:2.5px
    classDef service fill:#fce4ec,stroke:#c2185b,color:#000,stroke-width:2.5px
    classDef util fill:#f1f8e9,stroke:#689f38,color:#000,stroke-width:2.5px
    classDef routes fill:#fff9c4,stroke:#f57f17,color:#000,stroke-width:2.5px
    classDef middleware fill:#ffecb3,stroke:#ff6f00,color:#000,stroke-width:2.5px
    classDef domain fill:#e8f5e9,stroke:#388e3c,color:#000,stroke-width:2.5px
    classDef infra fill:#f3e5f5,stroke:#7b1fa2,color:#000,stroke-width:2.5px
    classDef external fill:#ffccbc,stroke:#d84315,color:#000,stroke-width:2.5px
    classDef comm fill:#b2dfdb,stroke:#00695c,color:#000,stroke-width:2.5px
    
    %% ═══════════════════════════════════════════════════════════════════
    %% FRONTEND - APLICACIÓN ANDROID (Kotlin)
    %% ═══════════════════════════════════════════════════════════════════
    subgraph FrontendApp["📱 FRONTEND - Aplicación Android CourseV (Kotlin/MVVM)"]
        direction TB
        
        subgraph UILayer["🎨 CAPA DE PRESENTACIÓN (UI Layer)"]
            direction TB
            MainActivity[MainActivity<br/>🏠 NavHostFragment]:::ui
            
            subgraph AgentFragments["🤖 Fragmentos de Agentes IA"]
                direction LR
                RLFragment[ReinforcementLearningFragment<br/>🎓 Agente Tutor]:::ui
                ChatFragment[ChatBotFragment<br/>📝 Agente Evaluador]:::ui
                DBQueryFragment[DatabaseQueryFragment<br/>📊 Agente Analista]:::ui
            end
            
            subgraph CourseFragments["📚 Fragmentos de Cursos"]
                direction LR
                CourseDetail[CourseDetailFragment]:::ui
                VideoHome[VideoHomeFragment]:::ui
                CourseTopic[CourseTopicFragment]:::ui
                TaskSubmit[TaskSubmissionsFragment]:::ui
            end
            
            subgraph UserFragments["👤 Fragmentos de Usuario"]
                direction LR
                Login[LoginFragment]:::ui
                Register[RegisterFragment]:::ui
                Profile[ProfileFragment]:::ui
                EditProfile[EditProfileFragment]:::ui
            end
            
            subgraph AdminFragments["🔐 Fragmentos Admin"]
                direction LR
                AdminDash[AdminDashboardFragment]:::ui
                StudentProg[StudentProgressFragment]:::ui
            end
        end
        
        subgraph ViewModelLayer["🧠 CAPA DE LÓGICA DE NEGOCIO (ViewModel Layer)"]
            direction LR
            AuthViewModel[AuthViewModel<br/>🔑 Autenticación]:::vm
            CourseViewModel[CourseViewModel<br/>📚 Cursos]:::vm
            VideoViewModel[VideoViewModel<br/>🎥 Videos]:::vm
            SelectTopicVM[SelectTopicViewModel<br/>📑 Selección]:::vm
            PersonaViewModel[PersonaViewModel<br/>👥 Perfiles]:::vm
        end
        
        subgraph RepositoryLayer["📦 CAPA DE REPOSITORIOS (Repository Pattern)"]
            direction LR
            CourseRepo[CourseRepository<br/>📚 Datos Cursos]:::repo
            UserRepo[UserRepository<br/>👤 Datos Usuario]:::repo
            TaskRepo[TaskRepository<br/>📝 Datos Tareas]:::repo
            VideoRepo[VideoRepository<br/>🎥 Datos Videos]:::repo
        end
        
        subgraph NetworkLayer["🌐 CAPA DE RED (Network Layer)"]
            direction LR
            MCPApiService[MCPApiService<br/>🔌 Cliente MCP]:::network
            SupabaseClient[SupabaseClient<br/>💾 Cliente DB]:::network
            RetrofitInstance[Retrofit + OkHttp<br/>🌍 Cliente HTTP]:::network
        end
        
        subgraph ServiceLayer["🔧 CAPA DE SERVICIOS (Service Layer)"]
            direction LR
            TTSService[TTSService<br/>🔊 Text-to-Speech]:::service
            NotificationService[NotificationService<br/>🔔 Notificaciones]:::service
            BackgroundSync[BackgroundSyncService<br/>🔄 Sincronización]:::service
        end
        
        subgraph UtilLayer["🛠️ CAPA DE UTILIDADES (Util Layer)"]
            direction LR
            SessionManager[SessionManager<br/>🔐 Sesión]:::util
            VideoManager[VideoManager<br/>🎬 Gestión Video]:::util
            NetworkUtils[NetworkUtils<br/>📡 Red]:::util
            PermissionHelper[PermissionHelper<br/>✅ Permisos]:::util
        end
        
        subgraph WorkLayer["⚙️ CAPA DE TAREAS EN SEGUNDO PLANO (Work Manager)"]
            direction LR
            LLMWorker[LLMBackgroundWorker<br/>🤖 Procesamiento IA]:::service
            BGTaskManager[BackgroundTaskManager<br/>⏱️ Gestor Tareas]:::service
        end
    end
    
    %% ═══════════════════════════════════════════════════════════════════
    %% CAPA DE COMUNICACIÓN
    %% ═══════════════════════════════════════════════════════════════════
    subgraph Communication["🌐 PROTOCOLO DE COMUNICACIÓN"]
        direction LR
        HTTPSProtocol[HTTPS/REST<br/>🔒 Encriptado TLS 1.3]:::comm
        WebSocketProtocol[WebSocket<br/>⚡ Realtime Bidireccional]:::comm
        JSONFormat[JSON Payload<br/>📄 Formato Datos]:::comm
        MultipartForm[Multipart/Form-Data<br/>📎 Subida Archivos]:::comm
    end
    
    %% ═══════════════════════════════════════════════════════════════════
    %% BACKEND - SERVIDOR NODE.JS MCP
    %% ═══════════════════════════════════════════════════════════════════
    subgraph BackendServer["☁️ BACKEND - Servidor MCP Node.js (Express/Clean Architecture)"]
        direction TB
        
        subgraph RoutesLayer["🚪 CAPA DE RUTAS HTTP (Entry Points)"]
            direction TB
            
            subgraph CoreRoutes["Rutas Principales"]
                MCPRoutes[MCPRoutes.js<br/>📡 /api/mcp/*]:::routes
                RAGRoutes[RAGRoutes.js<br/>📚 /api/rag/*]:::routes
                LLMRoutes[llmRoutes.js<br/>🤖 /api/llm/*]:::routes
            end
            
            subgraph AppRoutes["Rutas de Aplicación"]
                HealthRoutes[HealthRoutes.js<br/>💚 /api/health]:::routes
                NotifRoutes[notificationRoutes.js<br/>🔔 /api/notifications/*]:::routes
                TTSRoutes[ttsRoutes.js<br/>🔊 /api/tts/*]:::routes
                VideoRoutes[videoProcessingRoutes.js<br/>🎥 /api/videos/*]:::routes
                PaymentRoutes[paymentRoutes.js<br/>💳 /api/payments/*]:::routes
                ExcelRoutes[excelRoutes.js<br/>📊 /api/excel/*]:::routes
            end
        end
        
        subgraph MiddlewareLayer["🛡️ CAPA DE MIDDLEWARE (Seguridad y Control)"]
            direction LR
            SecurityMW[SecurityMiddleware.js<br/>🔐 JWT + CORS]:::middleware
            RateLimiterMW[RateLimiter.js<br/>⏱️ Rate Limiting]:::middleware
            RequestLoggerMW[RequestLogger.js<br/>📝 Logging]:::middleware
            ErrorHandlerMW[ErrorHandler.js<br/>⚠️ Manejo Errores]:::middleware
        end
        
        subgraph DomainLayer["🎯 CAPA DE DOMINIO (Business Logic)"]
            direction TB
            
            subgraph CoreServices["Servicios Core"]
                MCPService[MCPService.js<br/>🧠 Orquestador MCP]:::domain
                RAGService[RAGService.js<br/>🔍 Búsqueda Vectorial]:::domain
                DocumentProcessor[DocumentProcessor.js<br/>📄 Procesamiento Docs]:::domain
            end
            
            subgraph Validators["Validadores"]
                ValidationSchemas[ValidationSchemas.js<br/>✅ Validación Joi]:::domain
            end
        end
        
        subgraph AppServicesLayer["⚙️ CAPA DE SERVICIOS DE APLICACIÓN"]
            direction TB
            
            subgraph UserServices["Servicios de Usuario"]
                UserService[UserService.js<br/>👤 Gestión Usuarios]:::domain
                NotificationSvc[NotificationService.js<br/>🔔 Notificaciones]:::domain
            end
            
            subgraph MediaServices["Servicios de Media"]
                TTSSvc[TTSService.js<br/>🔊 Text-to-Speech]:::domain
                VideoProcessSvc[VideoProcessingService.js<br/>🎥 Procesamiento Video]:::domain
                R2StorageSvc[R2StorageService.js<br/>☁️ Almacenamiento R2]:::domain
            end
            
            subgraph FinancialServices["Servicios Financieros"]
                PaymentSvc[PaymentService.js<br/>💳 Pagos]:::domain
            end
        end
        
        subgraph InfrastructureLayer["🏗️ CAPA DE INFRAESTRUCTURA (Integraciones Externas)"]
            direction TB
            
            subgraph AIInfra["🤖 Infraestructura IA"]
                LLMService[LLMService.js<br/>🧠 Cliente DeepSeek]:::infra
                EmbeddingService[EmbeddingService.js<br/>🔢 Generación Embeddings]:::infra
            end
            
            subgraph DatabaseInfra["💾 Infraestructura Base de Datos"]
                SupabaseService[SupabaseService.js<br/>🗄️ Cliente PostgreSQL]:::infra
                VectorStore[SupabaseVectorStore.js<br/>📊 pgvector Store]:::infra
            end
            
            subgraph CacheInfra["⚡ Infraestructura Caché"]
                CacheService[CacheService.js<br/>🔥 Redis Client]:::infra
            end
            
            subgraph LoggingInfra["📋 Infraestructura Logging"]
                Logger[Logger.js<br/>📝 Sistema Logs]:::infra
            end
        end
    end
    
    %% ═══════════════════════════════════════════════════════════════════
    %% SERVICIOS EXTERNOS
    %% ═══════════════════════════════════════════════════════════════════
    subgraph ExternalServices["🌍 SERVICIOS EXTERNOS EN LA NUBE"]
        direction TB
        
        subgraph AIProviders["🤖 Proveedores de IA"]
            DeepSeekAPI[DeepSeek V3.2 API<br/>🧠 Razonamiento + Embeddings]:::external
        end
        
        subgraph DatabaseProviders["💾 Proveedores de Base de Datos"]
            SupabaseDB[(Supabase PostgreSQL<br/>🗄️ DB Relacional + pgvector)]:::external
        end
        
        subgraph CacheProviders["⚡ Proveedores de Caché"]
            RedisCache[(Redis Cache<br/>🔥 Almacenamiento Temporal)]:::external
        end
        
        subgraph StorageProviders["☁️ Proveedores de Almacenamiento"]
            CloudflareR2[Cloudflare R2<br/>📦 Object Storage]:::external
        end
        
        subgraph NotificationProviders["🔔 Proveedores de Notificaciones"]
            FirebaseFCM[Firebase FCM<br/>📲 Push Notifications]:::external
        end
    end
    
    %% ═══════════════════════════════════════════════════════════════════
    %% CONEXIONES FRONTEND INTERNO
    %% ═══════════════════════════════════════════════════════════════════
    
    %% UI -> ViewModel
    RLFragment --> AuthViewModel
    ChatFragment --> CourseViewModel
    DBQueryFragment --> CourseViewModel
    Login --> AuthViewModel
    Register --> AuthViewModel
    Profile --> PersonaViewModel
    EditProfile --> PersonaViewModel
    CourseDetail --> CourseViewModel
    VideoHome --> VideoViewModel
    CourseTopic --> SelectTopicVM
    TaskSubmit --> CourseViewModel
    AdminDash --> AuthViewModel
    StudentProg --> CourseViewModel
    
    %% ViewModel -> Repository
    AuthViewModel --> UserRepo
    CourseViewModel --> CourseRepo
    VideoViewModel --> VideoRepo
    SelectTopicVM --> TaskRepo
    PersonaViewModel --> UserRepo
    
    %% Repository -> Network
    CourseRepo --> MCPApiService
    UserRepo --> SupabaseClient
    TaskRepo --> MCPApiService
    VideoRepo --> MCPApiService
    
    %% Network -> Retrofit
    MCPApiService --> RetrofitInstance
    SupabaseClient --> RetrofitInstance
    
    %% Services -> UI
    TTSService -.->|Audio| UILayer
    NotificationService -.->|Alerts| UILayer
    BackgroundSync -.->|Sync Status| RepositoryLayer
    
    %% Utils -> Layers
    SessionManager -.->|User Session| ViewModelLayer
    VideoManager -.->|Video Control| VideoViewModel
    NetworkUtils -.->|Network Status| RepositoryLayer
    PermissionHelper -.->|Permissions| UILayer
    
    %% Work Manager
    LLMWorker --> MCPApiService
    BGTaskManager --> BackgroundSync
    
    %% ═══════════════════════════════════════════════════════════════════
    %% CONEXIONES FRONTEND -> COMUNICACIÓN -> BACKEND
    %% ═══════════════════════════════════════════════════════════════════
    
    RetrofitInstance ==>|POST /api/mcp/generate-questions| HTTPSProtocol
    RetrofitInstance ==>|POST /api/rag/grade + PDF| MultipartForm
    RetrofitInstance ==>|POST /api/llm/text-to-sql| HTTPSProtocol
    RetrofitInstance ==>|POST /api/auth/login| HTTPSProtocol
    SupabaseClient ==>|Realtime Subscribe| WebSocketProtocol
    
    HTTPSProtocol --> MiddlewareLayer
    WebSocketProtocol --> SupabaseService
    MultipartForm --> MiddlewareLayer
    JSONFormat -.->|Formato| HTTPSProtocol
    
    %% ═══════════════════════════════════════════════════════════════════
    %% CONEXIONES BACKEND INTERNO
    %% ═══════════════════════════════════════════════════════════════════
    
    %% Middleware -> Routes
    MiddlewareLayer --> CoreRoutes
    MiddlewareLayer --> AppRoutes
    
    %% Routes -> Domain Services
    MCPRoutes --> MCPService
    RAGRoutes --> RAGService
    LLMRoutes --> LLMService
    NotifRoutes --> NotificationSvc
    TTSRoutes --> TTSSvc
    VideoRoutes --> VideoProcessSvc
    PaymentRoutes --> PaymentSvc
    
    %% Domain Services -> Infrastructure
    MCPService --> LLMService
    MCPService --> CacheService
    RAGService --> EmbeddingService
    RAGService --> VectorStore
    RAGService --> DocumentProcessor
    DocumentProcessor --> VectorStore
    
    UserService --> SupabaseService
    NotificationSvc --> SupabaseService
    VideoProcessSvc --> R2StorageSvc
    
    %% Validators
    ValidationSchemas -.->|Valida Schemas| RoutesLayer
    
    %% Logging
    Logger -.->|Logs| MiddlewareLayer
    Logger -.->|Logs| DomainLayer
    
    %% ═══════════════════════════════════════════════════════════════════
    %% CONEXIONES BACKEND -> SERVICIOS EXTERNOS
    %% ═══════════════════════════════════════════════════════════════════
    
    LLMService ==>|API Key + Streaming| DeepSeekAPI
    EmbeddingService ==>|Vectorización| DeepSeekAPI
    SupabaseService ==>|SQL Queries| SupabaseDB
    VectorStore ==>|pgvector Similarity Search| SupabaseDB
    CacheService ==>|Redis Protocol| RedisCache
    R2StorageSvc ==>|S3 Compatible API| CloudflareR2
    NotificationSvc ==>|FCM API| FirebaseFCM
    
    %% ═══════════════════════════════════════════════════════════════════
    %% FLUJO DE RESPUESTAS (BACKEND -> FRONTEND)
    %% ═══════════════════════════════════════════════════════════════════
    
    DeepSeekAPI -.->|Respuesta IA Stream| LLMService
    SupabaseDB -.->|Resultados SQL| SupabaseService
    RedisCache -.->|Datos Cacheados| CacheService
    CloudflareR2 -.->|URLs Archivos| R2StorageSvc
    
    LLMService -.->|JSON Response| MCPRoutes
    RAGService -.->|Calificación + Feedback| RAGRoutes
    CoreRoutes -.->|HTTPS Response| MiddlewareLayer
    MiddlewareLayer -.->|JSON| HTTPSProtocol
    
    HTTPSProtocol -.->|Response Body| RetrofitInstance
    WebSocketProtocol -.->|Realtime Events| SupabaseClient
    
    RetrofitInstance -.->|LiveData Update| RepositoryLayer
    RepositoryLayer -.->|StateFlow Emit| ViewModelLayer
    ViewModelLayer -.->|UI State| UILayer
```

<div align="center">
<sub><b>CourseV - Arquitectura Completa del Sistema</b> — Vista integral mostrando Frontend Android (Kotlin/MVVM), Protocolo de Comunicación (HTTPS/WebSocket), Backend MCP (Node.js/Clean Architecture) y Servicios Externos en la Nube. El diagrama muestra todas las capas, servicios y el flujo bidireccional de datos desde la UI del usuario hasta DeepSeek V3.2.</sub>
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
