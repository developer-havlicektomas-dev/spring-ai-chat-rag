package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

@Configuration
@EnableConfigurationProperties(RagProperties::class)
class AiConfig {

    /** ChatClient that retrieves relevant doc chunks from Qdrant before every call (RAG). */
    @Bean
    fun chatClient(
        builder: ChatClient.Builder,
        vectorStore: VectorStore,
        props: RagProperties,
        @Value("classpath:prompts/rag-prompt.st") ragPrompt: Resource,
    ): ChatClient =
        builder
            .defaultSystem("You are a concise Kotlin tutor. Prefer short answers with a small code example.")
            .defaultAdvisors(
                QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(
                        SearchRequest.builder()
                            .topK(props.topK)
                            .similarityThreshold(props.similarityThreshold)
                            .build()
                    )
                    // Our own template: must keep the {query} and {question_answer_context} placeholders.
                    .promptTemplate(PromptTemplate(ragPrompt))
                    .build(),
                // Logs the full prompt (incl. retrieved context) at DEBUG; see logging level in application.yml.
                SimpleLoggerAdvisor(),
            )
            .build()
}
