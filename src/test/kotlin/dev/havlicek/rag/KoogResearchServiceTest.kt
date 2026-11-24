package dev.havlicek.rag

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.ai.support.ToolCallbacks

/** Canned LLM replies in order + a copy of every prompt it was sent (to check what memory put into it). */
private class RecordingExecutor(vararg replies: Message.Assistant) : PromptExecutor() {
    private val queue = ArrayDeque(replies.toList())
    val prompts = mutableListOf<Prompt>()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        prompts += prompt
        return queue.removeFirstOrNull() ?: error("no scripted reply left")
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
    override fun close() {}
}

private fun reply(text: String, input: Int = 100, output: Int = 20) =
    Message.Assistant(text, ResponseMetaInfo.create(ai.koog.utils.time.KoogClock.System, inputTokensCount = input, outputTokensCount = output))
private fun call(id: String, tool: String, args: String) =
    Message.Assistant(MessagePart.Tool.Call(id, tool, args), ResponseMetaInfo.create(ai.koog.utils.time.KoogClock.System, inputTokensCount = 100, outputTokensCount = 10))

class KoogResearchServiceTest {
    private val docsService: DocsService = mock(DocsService::class.java)
    private val tools = ResearchTools(docsService, WebPageFetcher(listOf("kotlinlang.org")))
    private val memory = AgentConversationMemory()
    private val meters = SimpleMeterRegistry()
    private fun service(executor: PromptExecutor) = KoogResearchService(executor, tools, memory, meters)

    init {
        given(docsService.search("data class copy", 4)).willReturn(listOf(SearchHit("data-classes.md", 0.9, "copy() copies an object.")))
    }

    @Test
    fun `runs several tools in one question and traces LLM calls, tool calls and tokens`() = runBlocking {
        val executor = RecordingExecutor(
            call("c1", "searchKotlinDocs", """{"query":"data class copy"}"""),
            call("c2", "calculate", """{"expression":"3 * 14"}"""),
            call("c3", "fetchWebPage", """{"url":"https://evil.example/"}"""),
            reply("copy() copies an object (data-classes.md); 3 * 14 = 42 (calculator)."),
        )

        val answer = service(executor).research("What does copy() do, and what is 3 * 14?", "t-1")

        assertEquals(listOf("searchKotlinDocs", "calculate", "fetchWebPage"), answer.toolCalls.map { it.tool })
        assertTrue(answer.toolCalls[0].resultPreview.contains("data-classes.md"))
        assertEquals("3 * 14 = 42", answer.toolCalls[1].resultPreview)
        assertFalse(answer.toolCalls[2].ok) // blocked by the allow-list, reported back to the model as an error text
        assertEquals(4, answer.llmCalls)
        assertEquals(400, answer.inputTokens)
        assertEquals(50, answer.outputTokens)
        assertEquals(listOf("llm", "tool", "llm", "tool", "llm", "tool", "llm"), answer.trace.filter { it.type != "node" }.map { it.type })
        assertTrue(answer.trace.any { it.type == "node" })
        // all three tools were offered to the model; Micrometer saw the tool calls
        assertEquals(3.0, meters.find("agent.tool.calls").tag("engine", "koog").timers().sumOf { it.count().toDouble() })
    }

    @Test
    fun `remembers facts across turns of the same conversation only`() = runBlocking {
        val executor = RecordingExecutor(
            reply("Noted: your project uses Kotlin 2.3."),
            reply("You said your project uses Kotlin 2.3."),
            reply("I don't know which Kotlin version you use."),
        )
        val service = service(executor)

        service.research("Remember: my project uses Kotlin 2.3.", "m-1")
        val second = service.research("Which Kotlin version does my project use?", "m-1")
        service.research("Which Kotlin version does my project use?", "m-2")

        val turn2 = executor.prompts[1].messages.map { it.textContent() }
        assertTrue(turn2.contains("Remember: my project uses Kotlin 2.3."), turn2.toString())
        assertTrue(turn2.contains("Noted: your project uses Kotlin 2.3."), turn2.toString())
        assertEquals(1, executor.prompts[1].messages.count { it is Message.System }) // memory does not duplicate the system prompt
        assertFalse(executor.prompts[2].messages.any { it.textContent().contains("Kotlin 2.3") }) // other conversation is isolated
        assertEquals(4, second.memoryMessages)
    }

    @Test
    fun `memory keeps only the dialogue, not tool calls and results`() = runBlocking {
        val executor = RecordingExecutor(call("c1", "calculate", """{"expression":"2 + 2"}"""), reply("2 + 2 = 4"))
        service(executor).research("What is 2 + 2?", "m-3")

        assertEquals(
            listOf(MemoryMessage("user", "What is 2 + 2?"), MemoryMessage("assistant", "2 + 2 = 4")),
            memory.show("m-3"),
        )
    }

    @Test
    fun `the memory window keeps the system prompt and the newest dialogue`() {
        val meta = ai.koog.prompt.message.RequestMetaInfo.Empty
        val messages = listOf(Message.System("sys", meta)) +
            (1..30).map { Message.User("q$it", meta) } +
            call("c9", "calculate", """{"expression":"1+1"}""")
        val kept = AgentConversationMemory.dialogueWindow(4).preprocess(messages)
        assertEquals(listOf("sys", "q27", "q28", "q29", "q30"), kept.map { it.textContent() })
    }

    @Test
    fun `Spring AI sees the very same three tools`() {
        val springNames = ToolCallbacks.from(tools).map { it.toolDefinition.name() }.toSet()
        assertEquals(setOf("searchKotlinDocs", "calculate", "fetchWebPage"), springNames)
    }
}
