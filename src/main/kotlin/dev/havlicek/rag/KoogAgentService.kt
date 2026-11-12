package dev.havlicek.rag

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Collections

data class ToolCallInfo(val tool: String, val args: String)
data class AgentAnswer(val answer: String, val toolCalls: List<ToolCallInfo>, val model: String)

/**
 * Day 9: a Koog AIAgent on top of the bridged PromptExecutor (Day 8).
 * Unlike /chat/ask (retrieval always runs first), the agent decides itself whether to call
 * the searchKotlinDocs tool, possibly several times, and loops until it has a final answer.
 */
@Service
class KoogAgentService(
    private val promptExecutor: PromptExecutor,
    docsTools: KotlinDocsTools,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val model = OpenAIModels.Chat.GPT4oMini

    // The registry is stateless, so one instance is shared by all requests.
    private val toolRegistry = ToolRegistry { tools(docsTools) }

    suspend fun ask(question: String): AgentAnswer {
        val toolCalls = Collections.synchronizedList(mutableListOf<ToolCallInfo>())

        // An AIAgent holds per-run state, so build a fresh (cheap) one per request.
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            toolRegistry = toolRegistry,
            systemPrompt = """
                You are a concise Kotlin tutor.
                For questions about the Kotlin language, call searchKotlinDocs first and base your answer on what it returns.
                Mention the source file names you used. If the docs do not cover it, say so.
                For greetings or questions not about Kotlin, answer directly without tools.
            """.trimIndent(),
            temperature = 0.2,
            maxIterations = 10, // safety cap on LLM <-> tool round-trips
        ) {
            install(EventHandler) {
                onToolCallStarting { ctx ->
                    log.info("Agent tool call: {}({})", ctx.toolName, ctx.toolArgs)
                    toolCalls += ToolCallInfo(ctx.toolName, ctx.toolArgs.toString())
                }
            }
        }

        val answer = agent.run(question)
        return AgentAnswer(answer, toolCalls.toList(), model.id)
    }
}
