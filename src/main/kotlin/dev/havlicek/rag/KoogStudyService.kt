package dev.havlicek.rag

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.asMermaidDiagram
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import org.springframework.stereotype.Service
import java.util.Collections

data class StudyAnswer(
    val pack: StudyPack,
    /** false = the structured step failed and pack.answer is the plain draft */
    val structured: Boolean,
    /** true only if the tool returned docs AND the model says they cover the question (labeled fallback otherwise) */
    val grounded: Boolean,
    val nodes: List<String>,
    val toolCalls: List<ToolCallInfo>,
    val model: String,
    val latencyMs: Long,
    val error: String? = null,
)

/** F6: runs [kotlinStudyStrategy] as a Koog AIAgent and reports which nodes / tools ran. */
@Service
class KoogStudyService(
    private val promptExecutor: PromptExecutor,
    docsTools: KotlinDocsTools,
) {
    private val model = OpenAIModels.Chat.GPT4oMini
    private val toolRegistry = ToolRegistry { tools(docsTools) }
    // The graph itself is immutable; per-run state lives in the agent's storage, so one instance is enough.
    private val strategy = kotlinStudyStrategy(fixingModel = model)

    val mermaid: String get() = strategy.asMermaidDiagram()

    suspend fun study(question: String): StudyAnswer {
        val nodes = Collections.synchronizedList(mutableListOf<String>())
        val toolCalls = Collections.synchronizedList(mutableListOf<ToolCallInfo>())
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            strategy = strategy,
            toolRegistry = toolRegistry,
            systemPrompt = """
                You are a Kotlin tutor preparing study material.
                Step 1: call searchKotlinDocs (1-3 focused queries) for any Kotlin question. Never answer Kotlin questions from memory.
                Step 2: answer concisely using only the search results and name the source files.
                If the question is not about Kotlin or the docs do not cover it, say that plainly instead of guessing.
            """.trimIndent(),
            temperature = 0.2,
            maxIterations = 20, // each node visit counts; a 2-search run is ~10
        ) {
            install(EventHandler) {
                onNodeExecutionStarting { ctx -> ctx.node.name.takeUnless { it.startsWith("__") }?.let(nodes::add) }
                onToolCallStarting { ctx -> toolCalls += ToolCallInfo(ctx.toolName, ctx.toolArgs.toString()) }
            }
        }
        val started = System.nanoTime()
        val run = agent.run(question)
        return run.toAnswer(nodes.toList(), toolCalls.toList(), model.id, (System.nanoTime() - started) / 1_000_000)
    }
}

/** Keeps only sources the tool really returned, so a hallucinated file name never reaches the client. */
internal fun StudyRun.toAnswer(nodes: List<String>, toolCalls: List<ToolCallInfo>, model: String, latencyMs: Long): StudyAnswer {
    val checked = pack.copy(sources = pack.sources.filter { it in docSources }.ifEmpty { if (pack.coveredByDocs) docSources else emptyList() })
    return StudyAnswer(
        pack = checked,
        structured = structured,
        grounded = pack.coveredByDocs && docSources.isNotEmpty(),
        nodes = nodes,
        toolCalls = toolCalls,
        model = model,
        latencyMs = latencyMs,
        error = error,
    )
}
