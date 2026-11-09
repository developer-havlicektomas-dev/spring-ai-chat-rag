package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.document.Document
import org.springframework.stereotype.Service

data class SourceRef(val source: String?, val score: Double?)
data class RagAnswer(val answer: String, val sources: List<SourceRef>)

@Service
class ChatService(private val chatClient: ChatClient) {

    /**
     * Grounded answer plus the chunks the advisor retrieved, so callers can show citations.
     * [source] optionally limits retrieval to one doc file (e.g. "coroutines.md").
     */
    fun ask(question: String, source: String? = null): RagAnswer {
        val response = chatClient.prompt()
            .user(question)
            .advisors { spec ->
                if (source != null) spec.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "source == '$source'")
            }
            .call()
            .chatClientResponse()

        @Suppress("UNCHECKED_CAST")
        val docs = response.context()[QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS] as? List<Document> ?: emptyList()
        return RagAnswer(
            answer = response.chatResponse()?.result?.output?.text.orEmpty(),
            sources = docs.map { SourceRef(it.metadata["source"]?.toString(), it.score) },
        )
    }
}
