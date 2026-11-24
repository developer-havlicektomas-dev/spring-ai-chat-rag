package dev.havlicek.rag

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Service
import java.util.UUID

/** One engine's result in POST /kotlin/agent/compare (and the body of POST /kotlin/chat/tools). */
data class EngineRun(
    val engine: String,
    val answer: String,
    val toolCalls: List<ToolCallTrace>,
    val llmCalls: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
    val error: String? = null,
)

data class CompareAnswer(val question: String, val koog: EngineRun, val springAi: EngineRun)

/**
 * F7: the same ResearchTools via plain Spring AI tool calling. ChatClient + OpenAiChatModel run the
 * LLM <-> tool loop internally (ToolCallingManager); there is no graph, no per-node events and no memory here
 * (single turn, so the comparison is fair). To still see the tool calls, each ToolCallback is wrapped in a
 * recorder per request. Spring AI also emits its own observations (spring.ai.tool, gen_ai.client.operation).
 */
@Service
class SpringToolsService(
    builder: ChatClient.Builder,
    tools: ResearchTools,
    private val meters: MeterRegistry,
) {
    private val toolCallbacks: List<ToolCallback> = ToolCallbacks.from(tools).toList()
    private val chatClient = builder.clone().defaultSystem(RESEARCH_SYSTEM_PROMPT).build()

    fun ask(question: String): EngineRun {
        val trace = AgentTraceRecorder("spring-ai", meters)
        val response = chatClient.prompt()
            .user(question)
            .options(OpenAiChatOptions.builder().model(MODEL).temperature(0.2))
            .toolCallbacks(toolCallbacks.map { RecordingToolCallback(it, trace) })
            .call()
            .chatResponse()
        val usage = response?.metadata?.usage
        return EngineRun(
            engine = "spring-ai",
            answer = response?.result?.output?.text.orEmpty(),
            toolCalls = trace.toolCalls(),
            llmCalls = null, // hidden inside the model's internal tool loop
            inputTokens = usage?.promptTokens,
            outputTokens = usage?.completionTokens,
            latencyMs = trace.finish(),
        )
    }

    private class RecordingToolCallback(private val delegate: ToolCallback, private val trace: AgentTraceRecorder) : ToolCallback {
        override fun getToolDefinition() = delegate.toolDefinition
        override fun getToolMetadata() = delegate.toolMetadata
        override fun call(toolInput: String): String = record(toolInput) { delegate.call(toolInput) }
        override fun call(toolInput: String, toolContext: ToolContext?): String = record(toolInput) { delegate.call(toolInput, toolContext) }

        private fun record(input: String, block: () -> String): String {
            val key = "tool:" + UUID.randomUUID()
            trace.started(key)
            return try {
                block().also { trace.tool(key, toolDefinition.name(), input, it.toResultText()) }
            } catch (e: RuntimeException) {
                trace.tool(key, toolDefinition.name(), input, "Error: ${e.message}", ok = false)
                throw e
            }
        }
    }

    companion object {
        const val MODEL = "gpt-4o-mini"
    }
}
