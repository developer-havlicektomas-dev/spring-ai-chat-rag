package dev.havlicek.rag

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.feature.writer.FeatureMessageLogWriter
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.tracing.feature.Tracing
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.MessagePart
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

data class ResearchAnswer(
    val conversationId: String,
    val answer: String,
    val toolCalls: List<ToolCallTrace>,
    val trace: List<TraceEvent>,
    val llmCalls: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    /** messages ChatMemory holds for this conversation after the run (user questions + final answers) */
    val memoryMessages: Int,
    val model: String,
    val latencyMs: Long,
)

/** Shared by the Koog agent and the Spring AI ChatClient so /kotlin/agent/compare compares like with like. */
val RESEARCH_SYSTEM_PROMPT = """
    You are a research assistant for a Kotlin developer. Tools:
    - searchKotlinDocs: local Kotlin docs. Use it first for Kotlin language questions.
    - fetchWebPage: allow-listed docs sites (kotlinlang.org, docs.spring.io, docs.koog.ai, api.koog.ai). Use it for
      Spring / Koog questions or when the local docs do not cover something. Pass a concrete page URL.
    - calculate: use it for ANY arithmetic, never compute in your head.
    Use earlier messages of this conversation: if the user told you facts (versions, project details), apply them.
    Keep answers short and say where each fact came from (doc file, URL, calculator, or the user).
""".trimIndent()

/**
 * F7: Koog research agent = default single-run strategy (LLM <-> tools loop) + 3 tools + 3 features:
 * - ChatMemory: loads/stores the dialogue per conversationId (agent.run(question, conversationId))
 * - Tracing: Koog's own event trace -> log (logger "koog.trace", DEBUG level)
 * - EventHandler: our structured trace (LLM calls with tokens, tool calls with args/latency, nodes) + Micrometer
 */
@Service
class KoogResearchService(
    private val promptExecutor: PromptExecutor,
    tools: ResearchTools,
    private val memory: AgentConversationMemory,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val traceLog = LoggerFactory.getLogger("koog.trace")
    private val model = OpenAIModels.Chat.GPT4oMini
    private val toolRegistry = ToolRegistry { tools(tools) }

    suspend fun research(question: String, conversationId: String?): ResearchAnswer {
        val id = conversationId ?: UUID.randomUUID().toString()
        val trace = AgentTraceRecorder("koog", meters)
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            toolRegistry = toolRegistry,
            systemPrompt = RESEARCH_SYSTEM_PROMPT,
            temperature = 0.2,
            maxIterations = 20, // node visits; a 3-tool run is ~8
        ) {
            install(ChatMemory) {
                chatHistoryProvider = memory
                addPreProcessor(AgentConversationMemory.dialogueWindow(MEMORY_WINDOW)) // system prompt + last 10 Q&A pairs
            }
            install(Tracing) {
                addMessageProcessor(TraceFeatureMessageLogWriter.create(traceLog, FeatureMessageLogWriter.LogLevel.DEBUG))
            }
            install(EventHandler) {
                onNodeExecutionStarting { ctx -> trace.started("node:${ctx.node.name}") }
                onNodeExecutionCompleted { ctx -> ctx.node.name.takeUnless { it.startsWith("__") }?.let(trace::node) }
                onLLMCallStarting { ctx -> trace.started("llm:${ctx.eventId}") }
                onLLMCallCompleted { ctx ->
                    val response = ctx.response
                    trace.llm(
                        key = "llm:${ctx.eventId}",
                        model = ctx.model.id,
                        requested = response?.parts.orEmpty().filterIsInstance<MessagePart.Tool.Call>().map { it.tool },
                        inputTokens = response?.metaInfo?.inputTokensCount,
                        outputTokens = response?.metaInfo?.outputTokensCount,
                    )
                }
                onToolCallStarting { ctx -> trace.started("tool:${ctx.toolCallId ?: ctx.toolName}") }
                onToolCallCompleted { ctx ->
                    trace.tool("tool:${ctx.toolCallId ?: ctx.toolName}", ctx.toolName, ctx.toolArgs.toString(), ctx.toolResult.toResultText())
                }
                onToolCallFailed { ctx ->
                    trace.tool("tool:${ctx.toolCallId ?: ctx.toolName}", ctx.toolName, ctx.toolArgs.toString(), "Error: ${ctx.message}", ok = false)
                }
            }
        }
        val answer = agent.run(question, id)
        val latency = trace.finish()
        log.info("research conversation={} tools={} llmCalls={} latency={}ms", id, trace.toolCalls().map { it.tool }, trace.llmCalls(), latency)
        return ResearchAnswer(
            conversationId = id,
            answer = answer,
            toolCalls = trace.toolCalls(),
            trace = trace.events(),
            llmCalls = trace.llmCalls(),
            inputTokens = trace.inputTokens(),
            outputTokens = trace.outputTokens(),
            memoryMessages = memory.size(id),
            model = model.id,
            latencyMs = latency,
        )
    }

    fun memory(conversationId: String): List<MemoryMessage>? = memory.show(conversationId)
    fun forget(conversationId: String) = memory.forget(conversationId)

    companion object {
        const val MEMORY_WINDOW = 20
    }
}

/** Koog hands tool results over as JSON; our tools return strings, so unwrap the JSON string literal. */
internal fun Any?.toResultText(): String {
    val raw = this?.toString() ?: return ""
    return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"'))
        raw.substring(1, raw.length - 1).replace("\\n", "\n").replace("\\\"", "\"")
    else raw
}
