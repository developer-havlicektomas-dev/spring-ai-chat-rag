package dev.havlicek.rag

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.rag.Query
import kotlin.test.Test
import kotlin.test.assertEquals

class FollowUpQueryTransformerTest {
    private var calls = 0
    private val transformer = FollowUpQueryTransformer { q -> calls++; q.mutate().text("standalone: ${q.text()}").build() }

    @Test
    fun `first turn skips the rewrite call`() {
        val q = Query.builder().text("What is a sealed class?")
            .history(listOf(SystemMessage("tutor"), UserMessage("What is a sealed class?"))).build()
        assertEquals("What is a sealed class?", transformer.transform(q).text())
        assertEquals(0, calls)
    }

    @Test
    fun `follow-up is rewritten`() {
        val q = Query.builder().text("How do I make one?")
            .history(listOf(UserMessage("What is a sealed class?"), AssistantMessage("A restricted hierarchy."), UserMessage("How do I make one?")))
            .build()
        assertEquals("standalone: How do I make one?", transformer.transform(q).text())
        assertEquals(1, calls)
    }
}
