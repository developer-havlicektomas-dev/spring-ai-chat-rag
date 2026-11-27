package dev.havlicek.rag

import org.springframework.ai.session.CreateSessionRequest
import org.springframework.ai.session.Session
import org.springframework.ai.session.SessionAlreadyExistsException
import org.springframework.ai.session.SessionAttributes
import org.springframework.ai.session.SessionService
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class SessionSummary(
    val conversationId: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    /** user + assistant messages ever stored (compaction only changes what is sent to the model) */
    val messages: Int,
)

/**
 * F8: chat sessions owned by users. The owner is Spring AI's standard `principalName` session attribute, which
 * JdbcSessionRepository indexes in AI_SESSION_INDEX, so "my sessions" is `findByMetadata("principalName", user)`.
 * We create the session (with its owner) before the ChatClient call; SessionAdvisors then finds it by id.
 * A conversationId owned by somebody else behaves like an unknown one (404, no existence leak).
 */
@Service
class UserSessions(
    private val sessionService: SessionService,
    private val users: UserDirectory,
) {

    /** Returns the conversation id to use: the given one (must be ours or new) or a fresh one. */
    fun open(userId: String, conversationId: String?): String {
        users.touch(userId)
        val id = conversationId ?: UUID.randomUUID().toString()
        val existing = sessionService.findById(id)
        when {
            existing == null -> create(id, userId)
            !existing.isOwnedBy(userId) -> throw NoSuchElementException("Unknown conversation '$id'")
        }
        return id
    }

    /** The session if it exists and belongs to [userId]. */
    fun find(userId: String, conversationId: String): Session? =
        sessionService.findById(conversationId)?.takeIf { it.isOwnedBy(userId) }

    fun list(userId: String): List<SessionSummary> =
        sessionService.findByMetadata(SessionAttributes.PRINCIPAL_NAME, userId)
            .sortedByDescending { it.createdAt() }
            .map { SessionSummary(it.id(), it.createdAt(), it.expiresAt(), sessionService.getMessages(it.id()).size) }

    /** Deletes the session if it is ours; true if something was deleted. */
    fun delete(userId: String, conversationId: String): Boolean {
        find(userId, conversationId) ?: return false
        sessionService.delete(conversationId)
        return true
    }

    private fun create(id: String, userId: String) {
        try {
            sessionService.create(CreateSessionRequest.builder().id(id).metadata(SessionAttributes.PRINCIPAL_NAME, userId).build())
        } catch (_: SessionAlreadyExistsException) {
            // two requests raced to create the same id: fine if the winner is the same user
            if (sessionService.findById(id)?.isOwnedBy(userId) != true) throw NoSuchElementException("Unknown conversation '$id'")
        }
    }

    private fun Session.isOwnedBy(userId: String) = SessionAttributes.principalName(this) == userId
}
