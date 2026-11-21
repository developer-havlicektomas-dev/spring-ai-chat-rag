package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallDecision
import org.springframework.ai.model.tool.ToolCallDecisions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.support.ToolCallbacks
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingToolCall(val id: String, val tool: String, val arguments: String)

data class OpsAnswer(
    val conversationId: String,
    /** "answered" or "awaiting_confirmation" */
    val status: String,
    val answer: String?,
    val pendingToolCalls: List<PendingToolCall> = emptyList(),
)

/** 409 for the controller: the conversation is in the wrong state for this call. */
class ConversationStateException(message: String) : RuntimeException(message)

/**
 * F2: tool confirmation (Spring AI 2.1).
 *
 * Flow: /ops/ask -> model asks for a tool marked requiresConfirmation -> ToolCallingAdvisor pauses and returns the
 * reply with the awaiting call ids -> we park that reply server-side and return the calls to the client ->
 * /ops/confirm with approve/deny -> ToolCallingManager.executeToolCalls(prompt, pausedReply, decisions) runs the
 * approved calls (a denied call answers the model with the deny message) -> we send the history back to the model.
 */
@Service
class OpsAgentService(
    builder: ChatClient.Builder,
    tools: DocsAdminTools,
    private val toolCallingManager: ToolCallingManager,
) {
    private val toolCallbacks = ToolCallbacks.from(tools).toList()

    private val chatClient = builder.clone()
        .defaultSystem(
            """
            You are an ops assistant for a Kotlin docs vector store. Use the tools to answer and act.
            If a tool call is denied, say so briefly and do not retry it.
            """.trimIndent()
        )
        .defaultToolCallbacks(toolCallbacks)
        .build()

    /** The pending reply must come from storage the client can't edit - whoever can edit it can run any tool. */
    private class Conversation {
        val messages = mutableListOf<Message>()
        var paused: ChatResponse? = null
    }

    private val conversations = ConcurrentHashMap<String, Conversation>()

    fun ask(message: String, conversationId: String?): OpsAnswer {
        val id = conversationId ?: UUID.randomUUID().toString()
        val conversation = conversations.computeIfAbsent(id) { Conversation() }
        synchronized(conversation) {
            if (conversation.paused != null) {
                throw ConversationStateException("Conversation $id is waiting for a tool confirmation; call /kotlin/ops/confirm first")
            }
            conversation.messages += UserMessage(message)
            return callModel(id, conversation)
        }
    }

    /**
     * Approve or deny the paused tool calls. [toolCallIds] limits the decision to some calls (the rest get the
     * opposite); null means all awaiting calls. Calls that never needed confirmation are always allowed.
     */
    fun confirm(conversationId: String, approve: Boolean, toolCallIds: List<String>?, reason: String?): OpsAnswer {
        val conversation = conversations[conversationId]
            ?: throw NoSuchElementException("Unknown conversation $conversationId")
        synchronized(conversation) {
            val paused = conversation.paused
                ?: throw ConversationStateException("Conversation $conversationId has no tool call waiting for confirmation")
            val awaiting = ToolCallDecisions.awaitingDecision(paused).toSet()
            val reply = paused.results.first { it.output.hasToolCalls() }.output

            val decisions = ToolCallDecisions.toolCallKeys(reply).map { key ->
                val approved = when {
                    key !in awaiting -> true
                    toolCallIds == null || key in toolCallIds -> approve
                    else -> !approve
                }
                if (approved) ToolCallDecision.allow(key)
                else ToolCallDecision.deny(key, "The user denied this call" + (reason?.let { ": $it" } ?: "."))
            }

            // Runs the allowed calls and returns the history ending in [assistant tool calls, tool results].
            val result = toolCallingManager.executeToolCalls(
                Prompt(conversation.messages.toList(), ToolCallingChatOptions.builder().toolCallbacks(toolCallbacks).build()),
                paused,
                ToolCallDecisions(decisions),
            )
            conversation.messages.clear()
            conversation.messages += result.conversationHistory()
            conversation.paused = null
            return callModel(conversationId, conversation)
        }
    }

    private fun callModel(id: String, conversation: Conversation): OpsAnswer {
        val response = chatClient.prompt().messages(conversation.messages.toList()).call().chatResponse()
            ?: error("Model returned no response")

        if (ToolCallDecisions.isAwaitingDecision(response)) {
            conversation.paused = response
            val awaiting = ToolCallDecisions.awaitingDecision(response).toSet()
            val reply = response.results.first { it.output.hasToolCalls() }.output
            val pending = ToolCallDecisions.toolCallKeys(reply).zip(reply.toolCalls)
                .filter { (key, _) -> key in awaiting }
                .map { (key, call) -> PendingToolCall(key, call.name(), call.arguments()) }
            return OpsAnswer(id, "awaiting_confirmation", reply.text?.takeIf { it.isNotBlank() }, pending)
        }

        val output = response.result?.output ?: error("Model returned no generation")
        conversation.messages += output
        return OpsAnswer(id, "answered", output.text.orEmpty())
    }
}
