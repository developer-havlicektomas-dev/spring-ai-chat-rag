package dev.havlicek.rag

import ai.koog.prompt.message.Message
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.ConcurrentHashMap

/** F8: where [AgentConversationMemory] keeps Koog conversations (blocking API; the provider hops to Dispatchers.IO). */
interface AgentMemoryStore {
    fun save(conversationId: String, messages: List<Message>)
    fun load(conversationId: String): List<Message>?
    fun delete(conversationId: String)
}

class InMemoryAgentMemoryStore : AgentMemoryStore {
    private val conversations = ConcurrentHashMap<String, List<Message>>()
    override fun save(conversationId: String, messages: List<Message>) { conversations[conversationId] = messages }
    override fun load(conversationId: String): List<Message>? = conversations[conversationId]
    override fun delete(conversationId: String) { conversations.remove(conversationId) }
}

/**
 * F8: one JSONB row per conversation (V3__agent_memory.sql). Koog's ChatMemory always stores the whole windowed
 * history, so replacing a document is simpler than diffing message rows. Koog's Message is @Serializable.
 */
class JdbcAgentMemoryStore(private val jdbc: JdbcClient) : AgentMemoryStore {

    override fun save(conversationId: String, messages: List<Message>) {
        jdbc.sql(
            """
            INSERT INTO agent_memory (conversation_id, messages) VALUES (:id, CAST(:messages AS jsonb))
            ON CONFLICT (conversation_id) DO UPDATE SET messages = EXCLUDED.messages, updated_at = now()
            """.trimIndent()
        )
            .param("id", conversationId)
            .param("messages", json.encodeToString(serializer, messages))
            .update()
    }

    override fun load(conversationId: String): List<Message>? =
        jdbc.sql("SELECT messages::text FROM agent_memory WHERE conversation_id = :id")
            .param("id", conversationId)
            .query(String::class.java)
            .optional()
            .map { json.decodeFromString(serializer, it) }
            .orElse(null)

    override fun delete(conversationId: String) {
        jdbc.sql("DELETE FROM agent_memory WHERE conversation_id = :id").param("id", conversationId).update()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Message.serializer())
    }
}
