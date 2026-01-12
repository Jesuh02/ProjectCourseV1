<div align="center">

# ProjectCourseV1 AI

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![DeepSeek](https://img.shields.io/badge/AI-DeepSeek_V3.2-blue?style=for-the-badge)](https://deepseek.com)
[![Ollama](https://img.shields.io/badge/Ollama-Local_LLM-orange?style=for-the-badge)](https://ollama.com)
[![License](https://img.shields.io/badge/License-MIT-green.svg?style=for-the-badge)](LICENSE)

[Get Started](#-get-started)  [Features](#-key-features)  [Architecture](#-architecture-overview)  [Docs](#-documentation)

<img src="https://via.placeholder.com/800x400.png?text=ProjectCourseV1+AI+Dashboard" alt="Project Banner" width="100%" />

</div>

##  Recent Updates (v1.2) - 2026/01/12

- **DeepSeek-V3.2 Integration**: Now powering the backend for superior reasoning.
- **Text-to-Speech (TTS)**: Added to 5 core screens for accessibility.
- **Self-Correcting BI**: The \DatabaseQueryFragment\ now auto-corrects SQL errors.
- **MCP Protocol**: Full VS Code integration for database context.

##  Introduction

**ProjectCourseV1** is a next-generation educational platform that leverages **Local AI (Ollama)** and **Cloud AI (DeepSeek-V3)** to provide real-time feedback, adaptive learning, and business intelligence.

Unlike traditional LMS platforms, ProjectCourseV1 uses an **Agentic Architecture**:
- **Educator Agent**: Grades tasks automatically using RAG (\ChatBotFragment\).
- **Analyst Agent**: Converts natural language to SQL for instant reports (\DatabaseQueryFragment\).
- **Tutor Agent**: Generates unique reinforcement questions based on user history (\ReinforcementLearningFragment\).

##  Key Features

###  Adaptive Reinforcement Learning
*Powered by \ReinforcementLearningFragment.kt\ & \MCPService.js\*

The system analyzes your course progress, topic, and current task to generate **10 unique questions** tailored to your gaps.
- **Zero Repetition Guarantee**: The backend tracks every question ever asked to a user (\index.js\ history tracking).
- **Context-Aware**: Uses the specific metadata of the course content.
- **Dynamic Difficulty**: Adjusts based on previous answers.

###  Intelligent Task Grading (RAG)
*Powered by \ChatBotFragment.kt\*

Submit your homework (PDF/TXT) and get instant feedback.
- **Vector Search**: Uses RAG to compare your submission against the rubric.
- **File Analysis**: Automatically detects file types and extracts text.
- **Actionable Feedback**: "Not just a grade, but a guide."

###  Business Intelligence (Text-to-SQL)
*Powered by \DatabaseQueryFragment.kt\*

Ask questions like *"Which course has the lowest completion rate?"* and get real charts.
- **Natural Language Processing**: Converts English/Spanish to complex SQL.
- **Self-Correction**: If the SQL fails, the Agent analyzes the error and retries automatically.
- **Visualization**: Android native charts powered by real-time Supabase data.

##  Architecture Overview

| Component | Tech Stack | Responsibility |
|-----------|------------|----------------|
| **Mobile App** | Android, Kotlin, Jetpack Compose | UI, Local Inference, TTS |
| **Backend Core** | Node.js, Express, MCP | Orchestration, History Tracking |
| **Database** | Supabase (PostgreSQL) | User Data, Vector Store (pgvector) |
| **AI Engine** | DeepSeek-V3.2 (Cloud), Gemma3n (Local) | Reasoning, SQL Generation |

\\\mermaid
graph TD
    A[Mobile App] -->|HTTP/REST| B[MCP Backend]
    A -->|Ollama| C[Local LLM (Gemma3n)]
    B -->|SQL| D[Supabase DB]
    B -->|API| E[DeepSeek-V3.2]
    B -->|Vector Search| D
\\\

##  Get Started

### Prerequisites
- Android Studio Hedgehog or newer.
- JDK 17.
- Ollama running locally (for offline features).

### Installation

1. **Clone the repository**
   \\\ash
   git clone https://github.com/YourUser/ProjectCourseV1.git
   \\\

2. **Configure Ollama**
   \\\ash
   ollama serve
   ollama pull gemma3n:latest
   \\\

3. **Build Android App**
   Open in Android Studio and run:
   \\\ash
   ./gradlew assembleDebug
   \\\

##  Project Structure

| File/Folder | Description |
|-------------|-------------|
| \ReinforcementLearningFragment.kt\ | Logic for adaptive question generation. |
| \DatabaseQueryFragment.kt\ | UI for the BI Text-to-SQL agent. |
| \ChatBotFragment.kt\ | RAG-based Chat interface. |
| \distribucion_de_contexto/\ | Node.js MCP Backend source code. |

##  Related Links

- [Backend Documentation](distribucion_de_contexto/MCP-backendDeploy/README.md)
- [DeepSeek AI](https://deepseek.com)
- [Supabase](https://supabase.com)

---
<div align="center">
  <sub>Built with  using Generative AI</sub>
</div>
