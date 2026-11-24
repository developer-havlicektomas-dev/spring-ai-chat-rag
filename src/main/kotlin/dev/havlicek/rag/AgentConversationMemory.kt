package dev.havlicek.rag

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class MemoryMessage(val role: String, val text: String)

/**
 * F7: where Koog's ChatMemory feature keeps each conversation (key = conversationId = agent.run(input, sessionId)).
 * Same idea as Koog's InMemoryChatHistoryProvider, but our own so we can show / delete a conversation over HTTP.
 * F8: swap for a Postgres-backed provider (one row per message, Koog's Message is @Serializable -> JSONB),
 * like the Spring AI side moves to spring-ai-session-jdbc.
 */
@Component
class AgentConversationMemory : ChatHistoryProvider {
    private val conversations = ConcurrentHashMap<String, List<Message>>()

    override suspend fun store(conversationId: String, messages: List<Message>) {
        conversations[conversationId] = messages.toList()
    }

    override suspend fun load(conversationId: String): List<Message> = conversations[conversationId].orEmpty()

    /** Remembered dialogue messages (without the stored system prompt). */
    fun size(conversationId: String): Int = conversations[conversationId]?.count(::isDialogue) ?: 0

    fun show(conversationId: String): List<MemoryMessage>? =
        conversations[conversationId]?.filter(::isDialogue)?.map { MemoryMessage(it.role.name.lowercase(), it.textContent()) }

    fun forget(conversationId: String) {
        conversations.remove(conversationId)
    }

    companion object {
        /** User questions and final assistant answers; no system prompt, no tool calls / tool results. */
        fun isDialogue(message: Message): Boolean = when (message) {
            is Message.User -> message.parts.none { it is MessagePart.Tool.Result }
            is Message.Assistant -> message.parts.none { it is MessagePart.Tool.Call } && message.textContent().isNotBlank()
            else -> false
        }

        /**
         * ChatMemory preprocessor (runs on load and on store): first system message + the last [maxMessages] dialogue messages.
         * Why not the built-in filterMessages + windowSize?
         * - ChatMemory replaces the prompt with the loaded history (+ the new user message), so the stored history must
         *   keep the system prompt; a plain windowSize(n) would sooner or later cut it off.
         * - Dropping tool calls/results means the window can never split a tool call from its result (OpenAI rejects
         *   that) and old tool output does not bloat every later prompt. Trade-off: the agent re-runs a tool if it
         *   needs that data again.
         */
        fun dialogueWindow(maxMessages: Int) = object : ChatMemoryPreProcessor {
            override fun preprocess(messages: List<Message>): List<Message> =
                messages.filterIsInstance<Message.System>().take(1) + messages.filter(::isDialogue).takeLast(maxMessages)
        }
    }
}
