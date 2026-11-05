package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AiConfig {

    /** ChatClient that retrieves relevant doc chunks from Qdrant before every call (RAG). */
    @Bean
    fun chatClient(builder: ChatClient.Builder, vectorStore: VectorStore): ChatClient =
        builder
            .defaultSystem("You are a concise Kotlin tutor. Answer using the provided context; say so if it doesn't cover the question.")
            .defaultAdvisors(
                QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder().topK(4).similarityThreshold(0.3).build())
                    .build()
            )
            .build()
}
