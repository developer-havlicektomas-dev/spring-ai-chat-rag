package dev.havlicek.rag

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

data class MemoryMessage(val role: String, val text: String)

/**
 * F7: where Koog's ChatMemory feature keeps each conversation (key = conversationId = agent.run(input, sessionId)).
 * Same idea as Koog's InMemoryChatHistoryProvider, but our own so we can show / delete a conversation over HTTP.
 * F8: the messages live in an [AgentMemoryStore]: Postgres (agent_memory, Koog's @Serializable Message list as JSONB)
 * by default, a map with rag.persistence=memory - so the research agent also remembers across restarts.
 */
@Component
class AgentConversationMemory(private val store: AgentMemoryStore = InMemoryAgentMemoryStore()) : ChatHistoryProvider {

    override suspend fun store(conversationId: String, messages: List<Message>) =
        withContext(Dispatchers.IO) { store.save(conversationId, messages.toList()) }

    override suspend fun load(conversationId: String): List<Message> =
        withContext(Dispatchers.IO) { store.load(conversationId).orEmpty() }

    /** Remembered dialogue messages (without the stored system prompt). */
    fun size(conversationId: String): Int = store.load(conversationId)?.count(::isDialogue) ?: 0

    fun show(conversationId: String): List<MemoryMessage>? =
        store.load(conversationId)?.filter(::isDialogue)?.map { MemoryMessage(it.role.name.lowercase(), it.textContent()) }

    fun forget(conversationId: String) = store.delete(conversationId)

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
