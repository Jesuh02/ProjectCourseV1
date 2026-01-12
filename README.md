<div align="center">

# CourseV AI

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![DeepSeek](https://img.shields.io/badge/AI-DeepSeek_V3.2-blue?style=for-the-badge)](https://deepseek.com)
[![Cloud](https://img.shields.io/badge/Backend-Cloud_Native-blueviolet?style=for-the-badge)](https://railway.app)

[Get Started](#-get-started-with-coursev)  [Agent Zoo](#-agent-zoo)  [Architecture](#-architecture)  [Documentation](#-documentation)

<img src="https://via.placeholder.com/1200x400.png?text=CourseV+AI+Platform+Preview" alt="CourseV Banner" width="100%" />

</div>

##  News

- **[2026-01-12]**: **DeepSeek-V3.2 Integration**: We have upgraded our core reasoning engine to DeepSeek-V3.2, enabling high-precision SQL generation and logic.
- **[2026-01-10]**: **Unique Question Generation**: The Reinforcement Learning module now guarantees 100% unique questions by tracking user history hashes.
- **[2026-01-05]**: **Self-Correcting SQL**: The BI Agent can now detect SQL errors and fix them autonomously without user intervention.

## Introduction

**CourseV AI** is a state-of-the-art educational platform that bridges the gap between **Local AI** (privacy-first) and **Cloud AI** (high-reasoning). Unlike traditional LMS apps, CourseV runs on a fully **Agentic Architecture**.

The application connects to a cloud-based **Node.js MCP Backend** (index.js) which orchestrates interactions between the Supabase database and the **DeepSeek-V3.2** Large Language Model.

### Key Features

*   ** Adaptive Reinforcement Learning**: 
    Implemented in \ReinforcementLearningFragment.kt\, this feature analyzes the specific course topic and task context.
    *   **Logic**: It generates **10 Assessment Questions** tailored to the content.
    *   **Zero Duplication**: The backend (\index.js\) maintains a hash history of every question asked. If the LLM generates a similar question, it is rejected and regenerated. The next 10 questions are guaranteed to be completely different from the previous 10.

*   ** Cloud-Based Grading Agent**:
    Located in \ChatBotFragment.kt\, this agent acts as a personal teaching assistant.
    *   **Capability**: Users upload files (PDF/TXT), and the agent uses **RAG (Retrieval-Augmented Generation)** to grade the assignment against the rubric.
    *   **Intelligence**: It provides feedback, points out errors, and suggests improvements, all powered by the Cloud Backend.

*   ** Natural Language to SQL (Business Intelligence)**:
    Access real-time database insights via \DatabaseQueryFragment.kt\.
    *   **Text-to-SQL**: Convert questions like *"How many students failed the Java course?"* into executable SQL.
    *   **Self-Correction**: Uses DeepSeek-V3.2 to analyze SQL errors (e.g., missing columns) and rewrite the query automatically until it succeeds.

##  Agent Zoo

We employ specialized agents for different domains within the app:

| Agent Name | Primary Model | Source File | Function |
|:----------:|:-------------:|:-----------:|:---------|
| **Tutor** | DeepSeek-V3.2 | \ReinforcementLearningFragment.kt\ | Generates unique, non-repeating quizzes. |
| **Grader** | DeepSeek/Gemma | \ChatBotFragment.kt\ | Grades submissions with detailed feedback. |
| **Analyst** | DeepSeek-V3.2 | \DatabaseQueryFragment.kt\ | Converts English to SQL & visualizes data. |

##  Architecture

<div align="center">
<img src="https://via.placeholder.com/800x400.png?text=Architecture+Diagram:+App+->+MCP+Backend+->+DeepSeek" alt="Architecture" width="80%" />
</div>

The system follows a strict separation of concerns:
1.  **Frontend (Android)**: Handles UI, TTS (Text-to-Speech), and context gathering.
2.  **MCP Backend (Cloud)**: Located in \distribucion_de_contexto/\. Handles high-load AI processing.
3.  **Database (Supabase)**: Stores User History, Vector Embeddings, and Course Data.

##  Get Started with CourseV

### 1. Installation

Clone the repository and open it in Android Studio.

\\\ash
git clone https://github.com/Tencent-Hunyuan/HY-Motion-1.0.git # Example used for style comparison
git clone https://github.com/YourRepo/CourseV.git
cd CourseV
\\\

### 2. Backend Configuration

The backend is critical for the AI agents.

\\\ash
cd distribucion_de_contexto/MCP-backendDeploy
npm install
npm start
\\\

### 3. Build Android App

\\\ash
./gradlew assembleDebug
\\\

##  DeepSeek Configuration

CourseV utilizes **DeepSeek-V3.2** for its superior reasoning capabilities. Ensure your backend \.env\ file is configured:

\\\env
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxx
MODEL_VERSION=deepseek-chat-v3.2
\\\

## Acknowledgements

Special thanks to the open-source community and the teams behind **DeepSeek**, **Ollama**, and **Supabase**.

---
<div align="center">
  <sub>Designed with precision. Powered by CourseV AI.</sub>
</div>
