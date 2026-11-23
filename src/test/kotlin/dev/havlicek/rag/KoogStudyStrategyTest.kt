package dev.havlicek.rag

import ai.koog.agents.core.agent.asMermaidDiagram
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock

/** Plays back canned LLM replies in order, so the whole graph runs offline (no OpenAI, no Qdrant). */
private class ScriptedExecutor(vararg replies: Message.Assistant) : PromptExecutor() {
    private val queue = ArrayDeque(replies.toList())
    val toolsOffered = mutableListOf<List<String>>()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        toolsOffered += tools.map { it.name }
        return queue.removeFirstOrNull() ?: text("not json at all")
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
    override fun close() {}
}

private fun text(content: String) = Message.Assistant(content, ResponseMetaInfo.Empty)
private fun toolCall(args: String) = Message.Assistant(MessagePart.Tool.Call("call_1", "searchKotlinDocs", args), ResponseMetaInfo.Empty)

class KoogStudyStrategyTest {

    private val docsService: DocsService = mock(DocsService::class.java)
    private fun service(executor: PromptExecutor) = KoogStudyService(executor, KotlinDocsTools(docsService))

    init {
        given(docsService.search("sealed class subclasses", 4)).willReturn(
            listOf(SearchHit("sealed-classes.md", 0.9, "Direct subclasses must be declared in the same package and module."))
        )
    }

    @Test
    fun `runs research, tool loop and structured output into a typed StudyPack`() = runBlocking {
        val packJson = """
            {"answer":"Direct subclasses of a sealed class must be in the same package and module.",
             "keyPoints":["Same package","Same module"],
             "sources":["sealed-classes.md","made-up.md"],
             "quiz":[{"question":"Where must direct subclasses live?","answer":"Same package and module"}],
             "coveredByDocs":true}
        """.trimIndent()
        val executor = ScriptedExecutor(
            toolCall("""{"query":"sealed class subclasses","topK":4}"""),
            text("They must be in the same package and module (sealed-classes.md)."),
            text(packJson),
        )

        val answer = service(executor).study("Where can sealed subclasses live?")

        assertTrue(answer.structured)
        assertTrue(answer.grounded)
        assertEquals(listOf("research", "runTools", "collectSources", "readResults", "keepDraft", "toStudyPack", "unwrap"), answer.nodes)
        assertEquals("searchKotlinDocs", answer.toolCalls.single().tool)
        assertEquals(listOf("sealed-classes.md"), answer.pack.sources) // made-up.md was never returned by the tool
        assertEquals(1, answer.pack.quiz.size)
        assertTrue(executor.toolsOffered.first().contains("searchKotlinDocs"))
    }

    @Test
    fun `falls back to the plain draft when the structured step cannot be parsed`() = runBlocking {
        // no tool call, then garbage for the structured request and for every fixing-parser retry
        val executor = ScriptedExecutor(text("I can only help with Kotlin questions."))

        val answer = service(executor).study("What is the capital of France?")

        assertFalse(answer.structured)
        assertFalse(answer.grounded)
        assertEquals("I can only help with Kotlin questions.", answer.pack.answer)
        assertEquals(listOf("research", "keepDraft", "toStudyPack", "unwrap"), answer.nodes)
        assertTrue(answer.toolCalls.isEmpty())
        assertNotNull(answer.error)
    }

    @Test
    fun `extracts source tags from tool output`() {
        val output = "[source: a.md]\nfoo\n---\n[source: b.md]\nbar\n---\n[source: a.md]\nbaz"
        assertEquals(setOf("a.md", "b.md"), sourcesIn(output))
        assertTrue(sourcesIn("No matching docs found.").isEmpty())
    }

    @Test
    fun `not grounded when the model says the docs do not cover it`() {
        val run = StudyRun(
            StudyPack("Not covered.", emptyList(), listOf("coroutines.md"), emptyList(), coveredByDocs = false),
            structured = true, draft = "", docSources = listOf("coroutines.md"),
        )
        val answer = run.toAnswer(emptyList(), emptyList(), "gpt-4o-mini", 1)
        assertFalse(answer.grounded)
        assertEquals(listOf("coroutines.md"), answer.pack.sources)
    }

    @Test
    fun `graph renders as mermaid with all custom nodes`() {
        val diagram = kotlinStudyStrategy(OpenAIModels.Chat.GPT4oMini).asMermaidDiagram()
        listOf("research", "runTools", "collectSources", "readResults", "keepDraft", "toStudyPack", "unwrap")
            .forEach { assertTrue(diagram.contains(it), "missing $it in\n$diagram") }
    }
}
