# Microservicio: Distribución de Contexto para Granite/Ollama

Este microservicio en Node.js permite dividir o resumir el contexto de prompts enviados al modelo Granite/Ollama, asegurando que no se exceda el límite de 4096 tokens.

## ¿Cómo funciona?
- Recibe un prompt completo desde la app móvil (por ejemplo, desde `AIAnalysisService.kt`).
- Si el prompt supera los 4096 tokens:
  - Recorta y resume el contenido, manteniendo lo más relevante.
  - Envía el prompt ajustado al modelo Ollama.
- Devuelve la respuesta al cliente.

## Uso
1. Instala las dependencias:
   ```bash
   npm install
   ```
2. Inicia el microservicio:
   ```bash
   npm start
   ```
3. Realiza una petición POST a `/procesar-prompt` con el siguiente JSON:
   ```json
   {
     "prompt": "<PROMPT_COMPLETO>",
     "ollamaUrl": "http://localhost:11435" // URL de tu servidor Ollama
   }
   ```

## Ejemplo de integración con la app móvil
En `AIAnalysisService.kt`, cuando el prompt sea muy grande, envíalo a este microservicio antes de llamar a Ollama.

---

**Autor:** GitHub Copilot
