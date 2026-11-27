package dev.havlicek.rag

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One tool execution: what the model asked for, how long it took and a short preview of what came back. */
data class ToolCallTrace(val tool: String, val args: String, val latencyMs: Long, val ok: Boolean, val resultPreview: String)

/** One step of a run, in order. type = "llm" | "tool" | "node"; atMs = offset from the start of the run. */
data class TraceEvent(
    val type: String,
    val name: String,
    val atMs: Long,
    val durationMs: Long,
    val detail: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * F7: collects a structured trace for one agent run (fed by Koog EventHandler callbacks or the Spring AI tool wrapper)
 * and mirrors the important numbers into Micrometer:
 *   agent.tool.calls{engine,tool,outcome}  (timer)   agent.llm.calls{engine} (timer)   agent.llm.tokens{engine,type} (counter)
 * -> GET /actuator/metrics/agent.tool.calls?tag=engine:koog
 */
class AgentTraceRecorder(private val engine: String, private val meters: MeterRegistry) {
    private val t0 = System.nanoTime()
    private val events = mutableListOf<TraceEvent>()
    private val tools = mutableListOf<ToolCallTrace>()
    private val open = ConcurrentHashMap<String, Long>() // key -> start nanos

    private fun now() = System.nanoTime()
    private fun ms(nanos: Long) = nanos / 1_000_000

    fun started(key: String) {
        open[key] = now()
    }

    /** Returns (offset from run start, duration) for a span opened with [started]; unknown key = zero-length span. */
    private fun close(key: String): Pair<Long, Long> {
        val end = now()
        val start = open.remove(key) ?: end
        return ms(start - t0) to ms(end - start)
    }

    @Synchronized
    fun node(name: String) {
        val (at, duration) = close("node:$name")
        events += TraceEvent("node", name, at, duration)
    }

    @Synchronized
    fun llm(key: String, model: String, requested: List<String>, inputTokens: Int?, outputTokens: Int?) {
        val (at, duration) = close(key)
        events += TraceEvent(
            "llm", model, at, duration,
            detail = if (requested.isEmpty()) "final answer" else "requested tools: ${requested.joinToString()}",
            inputTokens = inputTokens, outputTokens = outputTokens,
        )
        Timer.builder("agent.llm.calls").tag("engine", engine).register(meters).record(Duration.ofMillis(duration))
        inputTokens?.let { meters.counter("agent.llm.tokens", "engine", engine, "type", "input").increment(it.toDouble()) }
        outputTokens?.let { meters.counter("agent.llm.tokens", "engine", engine, "type", "output").increment(it.toDouble()) }
    }

    @Synchronized
    fun tool(key: String, tool: String, args: String, result: String, ok: Boolean = !result.startsWith("Error:")) {
        val (at, duration) = close(key)
        val preview = result.replace(Regex("\\s+"), " ").take(160)
        tools += ToolCallTrace(tool, args, duration, ok, preview)
        events += TraceEvent("tool", tool, at, duration, detail = args)
        Timer.builder("agent.tool.calls").tag("engine", engine).tag("tool", tool).tag("outcome", if (ok) "ok" else "error")
            .register(meters).record(Duration.ofMillis(duration))
    }

    @Synchronized fun events(): List<TraceEvent> = events.sortedBy { it.atMs }
    @Synchronized fun toolCalls(): List<ToolCallTrace> = tools.toList()
    @Synchronized fun llmCalls(): Int = events.count { it.type == "llm" }
    @Synchronized fun inputTokens(): Int? = events.mapNotNull { it.inputTokens }.takeIf { it.isNotEmpty() }?.sum()
    @Synchronized fun outputTokens(): Int? = events.mapNotNull { it.outputTokens }.takeIf { it.isNotEmpty() }?.sum()
    fun elapsedMs(): Long = ms(now() - t0)

    fun finish(): Long = elapsedMs().also {
        Timer.builder("agent.runs").tag("engine", engine).register(meters).record(Duration.ofMillis(it))
    }
}
