import { ChatOllama } from "@langchain/community/chat_models/ollama";
import { HumanMessage, SystemMessage, ToolMessage, AIMessage } from "@langchain/core/messages";

export class LLMService {
    constructor() {
        // Initialize Ollama with Llama 3:8b
        this.model = new ChatOllama({
            baseUrl: process.env.OLLAMA_BASE_URL || "http://localhost:11434",
            model: "llama3:8b",
            temperature: 0,
            // Increase timeout to 5 minutes to allow for model loading
            timeout: 300000
        });
    }

    async testConnection() {
        if (!this.model) return { status: 'disabled', error: 'Model not initialized' };
        try {
            // Simple test
            await this.model.invoke("Hello");
            return { status: 'connected', provider: 'ollama', model: 'llama3:8b' };
        } catch (error) {
            console.error("LLM Connection failed:", error);
            return { status: 'error', error: error.message };
        }
    }

    /**
     * Generate a response from the LLM, potentially calling tools.
     * @param {Array} messages - History of messages
     * @param {Array} tools - List of tool definitions (JSON Schema)
     */
    async generateResponse(messages, tools = []) {
        if (!this.model) throw new Error("LLM disabled");
        try {
            const modelWithTools = this.model.bind({
                tools: tools.map(t => ({
                    type: "function",
                    function: t
                }))
            });

            const response = await modelWithTools.invoke(messages);
            return response;
        } catch (error) {
            console.error("Error generating LLM response:", error);
            throw error;
        }
    }
}