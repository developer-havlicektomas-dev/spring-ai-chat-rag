package dev.havlicek.rag

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import org.springframework.stereotype.Service

data class KoogAnswer(val answer: String, val model: String, val provider: String)

/**
 * Day 8: Koog bridge. The koog-spring-ai-v2 starter wraps our Spring AI OpenAI ChatModel
 * into a Koog PromptExecutor, so Koog reuses the same API key, model config and HTTP client.
 * No RAG here on purpose: this is a plain LLM call through Koog, the base for the Day 9 agent.
 */
@Service
class KoogBridgeService(private val promptExecutor: PromptExecutor) {

    // Must match a model the Spring AI ChatModel can serve; Koog passes its id down to Spring AI.
    private val model = OpenAIModels.Chat.GPT4oMini

    suspend fun ask(question: String): KoogAnswer {
        val prompt = prompt("koog-bridge") {
            system("You are a concise Kotlin tutor. Answer in at most 3 sentences.")
            user(question)
        }
        val reply = promptExecutor.execute(prompt, model)
        return KoogAnswer(reply.textContent(), model.id, model.provider.id)
    }
}
