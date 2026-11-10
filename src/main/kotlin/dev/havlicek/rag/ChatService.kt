package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.document.Document
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

data class SourceRef(val source: String?, val score: Double?)
data class RagAnswer(val answer: String, val sources: List<SourceRef>)

/** Token wrapped in JSON: SSE clients strip one leading space after "data:", which would eat spaces between words. */
data class TokenChunk(val text: String)

@Service
class ChatService(private val chatClient: ChatClient) {

    /**
     * Grounded answer plus the chunks the advisor retrieved, so callers can show citations.
     * [source] optionally limits retrieval to one doc file (e.g. "coroutines.md").
     */
    fun ask(question: String, source: String? = null): RagAnswer {
        val response = prompt(question, source).call().chatClientResponse()
        return RagAnswer(
            answer = response.text(),
            sources = response.sources(),
        )
    }

    /**
     * Same RAG pipeline, streamed as Server-Sent Events:
     * one `sources` event (as soon as retrieval is done), many `token` events, then a final `done` event.
     * Errors become an `error` event instead of a broken connection.
     */
    fun stream(question: String, source: String? = null): Flux<ServerSentEvent<Any>> {
        val sourcesSent = AtomicBoolean(false)
        val tokens = prompt(question, source).stream().chatClientResponse()
            .concatMap { chunk ->
                val events = buildList {
                    if (sourcesSent.compareAndSet(false, true)) add(event("sources", chunk.sources()))
                    chunk.text().takeIf { it.isNotEmpty() }?.let { add(event("token", TokenChunk(it))) }
                }
                Flux.fromIterable(events)
            }
        return tokens
            .concatWith(Flux.just(event("done", "[DONE]")))
            .onErrorResume { e -> Flux.just(event("error", e.message ?: "stream failed")) }
    }

    private fun prompt(question: String, source: String?) =
        chatClient.prompt()
            .user(question)
            .advisors { spec ->
                if (source != null) spec.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "source == '$source'")
            }

    private fun event(name: String, data: Any): ServerSentEvent<Any> =
        ServerSentEvent.builder(data).event(name).build()
}

private fun ChatClientResponse.text(): String = chatResponse()?.result?.output?.text.orEmpty()

@Suppress("UNCHECKED_CAST")
private fun ChatClientResponse.sources(): List<SourceRef> =
    (context()[QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS] as? List<Document> ?: emptyList())
        .map { SourceRef(it.metadata["source"]?.toString(), it.score) }
