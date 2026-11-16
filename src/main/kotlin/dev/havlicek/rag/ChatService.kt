package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.document.Document
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class SourceRef(val source: String?, val score: Double?)
data class RagAnswer(val answer: String, val sources: List<SourceRef>, val conversationId: String)
data class HistoryMessage(val role: String, val text: String)

/** Token wrapped in JSON: SSE clients strip one leading space after "data:", which would eat spaces between words. */
data class TokenChunk(val text: String)

@Service
class ChatService(
    private val chatClient: ChatClient,
    private val sessionService: SessionService,
) {

    /**
     * Grounded answer plus the retrieved chunks. Pass the returned [RagAnswer.conversationId] back
     * to continue the same conversation (F1); omit it to start a new one.
     * [source] optionally limits retrieval to one doc file (e.g. "coroutines.md").
     */
    fun ask(question: String, source: String? = null, conversationId: String? = null): RagAnswer {
        val id = conversationId ?: newConversationId()
        val response = prompt(question, source, id).call().chatClientResponse()
        return RagAnswer(answer = response.text(), sources = response.sources(), conversationId = id)
    }

    /**
     * Same RAG + session pipeline, streamed as Server-Sent Events:
     * `conversation` (id), `sources` (once retrieval is done), many `token` events, then `done`.
     * Errors become an `error` event instead of a broken connection.
     */
    fun stream(question: String, source: String? = null, conversationId: String? = null): Flux<ServerSentEvent<Any>> {
        val id = conversationId ?: newConversationId()
        val sourcesSent = AtomicBoolean(false)
        val tokens = prompt(question, source, id).stream().chatClientResponse()
            .concatMap { chunk ->
                val events = buildList {
                    if (sourcesSent.compareAndSet(false, true)) add(event("sources", chunk.sources()))
                    chunk.text().takeIf { it.isNotEmpty() }?.let { add(event("token", TokenChunk(it))) }
                }
                Flux.fromIterable(events)
            }
        return Flux.just(event("conversation", mapOf("conversationId" to id)))
            .concatWith(tokens)
            .concatWith(Flux.just(event("done", "[DONE]")))
            .onErrorResume { e -> Flux.just(event("error", e.message ?: "stream failed")) }
    }

    /** What the session currently feeds the model (after compaction), oldest first; null if unknown/expired. */
    fun history(conversationId: String): List<HistoryMessage>? {
        sessionService.findById(conversationId) ?: return null
        return sessionService.getActiveMessages(conversationId)
            .map { HistoryMessage(it.messageType.name.lowercase(), it.text.orEmpty()) }
    }

    fun forget(conversationId: String) = sessionService.delete(conversationId)

    private fun prompt(question: String, source: String?, conversationId: String) =
        chatClient.prompt()
            .user(question)
            .advisors(SessionAdvisors.session(conversationId))
            .advisors { spec ->
                if (source != null) spec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "source == '$source'")
            }

    private fun newConversationId() = UUID.randomUUID().toString()

    private fun event(name: String, data: Any): ServerSentEvent<Any> =
        ServerSentEvent.builder(data).event(name).build()
}

private fun ChatClientResponse.text(): String = chatResponse()?.result?.output?.text.orEmpty()

@Suppress("UNCHECKED_CAST")
private fun ChatClientResponse.sources(): List<SourceRef> =
    (context()[RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT] as? List<Document> ?: emptyList())
        .map { SourceRef(it.metadata["source"]?.toString(), it.score) }
