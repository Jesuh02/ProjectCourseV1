import { ChatOpenAI } from "@langchain/openai";
import { HumanMessage, SystemMessage, ToolMessage, AIMessage } from "@langchain/core/messages";

export class LLMService {
    constructor() {
        const apiKey = process.env.OPENAI_API_KEY;
        if (!apiKey) {
            console.warn("⚠️ OPENAI_API_KEY not found. LLM features will be disabled.");
            this.model = null;
            return;
        }
        this.model = new ChatOpenAI({
            modelName: "gpt-4-turbo-preview", // Or gpt-3.5-turbo
            temperature: 0,
            openAIApiKey: apiKey
        });
    }

    async testConnection() {
        if (!this.model) return { status: 'disabled', error: 'OPENAI_API_KEY missing' };
        try {
            // Simple test
            await this.model.invoke("Hello");
            return { status: 'connected', provider: 'openai' };
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
        if (!this.model) throw new Error("LLM disabled: OPENAI_API_KEY missing");
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