package dev.havlicek.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.session.DefaultSessionService
import org.springframework.ai.session.InMemorySessionRepository
import org.springframework.ai.session.SessionAttributes

/** F8: session ownership on the in-memory repository (the JDBC one is covered by PostgresPersistenceTest). */
class UserSessionsTest {
    private val sessionService = DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build()
    private val users = InMemoryUserDirectory()
    private val sessions = UserSessions(sessionService, users)

    @Test
    fun `open creates a session owned by the caller and registers the user`() {
        val id = sessions.open("alice", null)
        assertEquals("alice", SessionAttributes.principalName(sessionService.findById(id)!!))
        assertNotNull(users.find("alice"))
        assertEquals(id, sessions.open("alice", id)) // continuing is fine
    }

    @Test
    fun `another user's conversation looks unknown`() {
        val id = sessions.open("alice", "conv-1")
        assertThrows<NoSuchElementException> { sessions.open("bob", id) }
        assertNull(sessions.find("bob", id))
        assertEquals(false, sessions.delete("bob", id))
        assertNotNull(sessionService.findById(id))
    }

    @Test
    fun `list returns only the caller's sessions with message counts`() {
        val a1 = sessions.open("alice", "a-1")
        sessions.open("alice", "a-2")
        sessions.open("bob", "b-1")
        sessionService.appendMessage(a1, UserMessage("hi"))
        val list = sessions.list("alice")
        assertEquals(setOf("a-1", "a-2"), list.map { it.conversationId }.toSet())
        assertEquals(1, list.first { it.conversationId == "a-1" }.messages)
    }

    @Test
    fun `owner can delete, invalid user ids are rejected`() {
        val id = sessions.open("alice", null)
        assertTrue(sessions.delete("alice", id))
        assertNull(sessionService.findById(id))
        assertThrows<InvalidUserIdException> { sessions.open("bad user!", null) }
    }
}
