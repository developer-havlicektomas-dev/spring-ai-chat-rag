package dev.havlicek.rag

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pause -> confirm -> resume, with a scripted ChatModel and a mocked DocsService (no OpenAI, no Qdrant). */
class OpsAgentServiceTest {

    private val docsService = mock(DocsService::class.java)
    private val prompts = mutableListOf<Prompt>()

    /** First call: the model wants deleteDocSource. Later calls: echo the last tool result as the answer. */
    private val chatModel = object : ChatModel {
        // ToolCallingAdvisor only loops when the options support tools (real models like OpenAI do this already)
        override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

        override fun call(prompt: Prompt): ChatResponse {
            prompts += prompt
            val last = prompt.instructions.last()
            val output = if (last is ToolResponseMessage) {
                AssistantMessage("Done: " + last.responses.first().responseData())
            } else {
                AssistantMessage.builder()
                    .toolCalls(listOf(AssistantMessage.ToolCall("call_1", "function", "deleteDocSource", """{"source":"coroutines.md"}""")))
                    .build()
            }
            return ChatResponse(listOf(Generation(output)))
        }
    }

    private val service = OpsAgentService(
        ChatClient.builder(chatModel),
        DocsAdminTools(docsService),
        ToolCallingManager.builder().build(), // default policy: honour @Tool(requiresConfirmation = true)
    )

    @Test
    fun `destructive tool pauses and runs only after approval`() {
        `when`(docsService.deleteSource("coroutines.md")).thenReturn(7)

        val paused = service.ask("Delete the coroutines docs", "c-1")
        assertEquals("awaiting_confirmation", paused.status)
        assertEquals(listOf(PendingToolCall("call_1", "deleteDocSource", """{"source":"coroutines.md"}""")), paused.pendingToolCalls)
        verify(docsService, never()).deleteSource("coroutines.md")

        val done = service.confirm("c-1", approve = true, toolCallIds = null, reason = null)
        assertEquals("answered", done.status)
        assertTrue(done.answer!!.contains("Deleted 7 chunks of coroutines.md"))
        verify(docsService).deleteSource("coroutines.md")
    }

    @Test
    fun `denied tool never runs and the model gets the reason`() {
        service.ask("Delete the coroutines docs", "c-2")
        val done = service.confirm("c-2", approve = false, toolCallIds = null, reason = "keep them")

        verify(docsService, never()).deleteSource("coroutines.md")
        assertTrue(done.answer!!.contains("denied"))
        assertTrue(done.answer!!.contains("keep them"))
    }

    @Test
    fun `wrong-state calls are rejected`() {
        service.ask("Delete the coroutines docs", "c-3")
        assertFailsWith<ConversationStateException> { service.ask("hello?", "c-3") }
        service.confirm("c-3", approve = false, toolCallIds = null, reason = null)
        // nothing is pending any more, so a second confirm is a conflict too
        assertFailsWith<ConversationStateException> { service.confirm("c-3", approve = true, toolCallIds = null, reason = null) }
    }
}
