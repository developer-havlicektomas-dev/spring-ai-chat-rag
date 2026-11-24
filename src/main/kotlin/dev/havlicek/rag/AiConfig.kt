package dev.havlicek.rag

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.ai.chat.prompt.PromptTemplate
import org.springframework.ai.decision.DecisionModel
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever
import org.springframework.ai.session.DefaultSessionService
import org.springframework.ai.session.InMemorySessionRepository
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.advisor.SessionAdvisors
import org.springframework.ai.session.compaction.TurnCountTrigger
import org.springframework.ai.session.compaction.WindowCompactionStrategy
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import java.time.Duration

@Configuration
@EnableConfigurationProperties(RagProperties::class)
class AiConfig {

    /**
     * F1: Spring AI 2.1 SessionService (replaces ChatMemory + ChatMemoryRepository).
     * In-memory for now; swap the repository for spring-ai-session-jdbc to persist in Postgres (F8).
     */
    @Bean
    fun sessionService(props: RagProperties): SessionService =
        DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .defaultTimeToLive(Duration.ofMinutes(props.sessionTtlMinutes))
            .build()

    /**
     * Modular RAG: rewrite follow-ups -> retrieve from Qdrant (optional metadata filter) -> F3 post-process
     * (decision filter -> decision reranker -> sufficiency gate) -> augment the user message with numbered chunks.
     * Replaces the monolithic QuestionAnswerAdvisor from Days 4-7.
     */
    @Bean
    fun ragAdvisor(
        builder: ChatClient.Builder,
        vectorStore: VectorStore,
        decisionModel: DecisionModel, // F3: auto-configured OpenAiDecisionModel (OpenAI Decisions API)
        props: RagProperties,
        @Value("classpath:prompts/rag-prompt.st") ragPrompt: Resource,
        @Value("classpath:prompts/rag-empty-context.st") emptyPrompt: Resource,
        @Value("classpath:prompts/rag-fallback.st") fallbackPrompt: Resource,
    ): RetrievalAugmentationAdvisor =
        RetrievalAugmentationAdvisor.builder()
            // "And how do I make one?" -> "How do I create a sealed class in Kotlin?" (only when there is history)
            .queryTransformers(
                FollowUpQueryTransformer(CompressionQueryTransformer.builder().chatClientBuilder(builder.clone()).build())
            )
            .documentRetriever(
                VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .topK(props.topK)
                    .similarityThreshold(props.similarityThreshold)
                    .build()
            )
            // F3: each step is toggled by rag.* config (and per request for rerank/decision-filter)
            .documentPostProcessors(DecisionSteps.build(decisionModel, props))
            .queryAugmenter(
                GroundedOrFallbackAugmenter(
                    ContextualQueryAugmenter.builder()
                        .promptTemplate(PromptTemplate(ragPrompt))               // needs {query} and {context}
                        .emptyContextPromptTemplate(PromptTemplate(emptyPrompt)) // hard refusal when the gate is off
                        .documentFormatter(Citations::formatContext)             // F3: "[1] (coroutines.md#0)\n..."
                        .build(),
                    // F3: empty or insufficient context -> labeled general-knowledge answer instead of the refusal
                    fallback = PromptTemplate(fallbackPrompt).takeIf { props.sufficiency.enabled },
                )
            )
            .build()

    /** F7: the research agent's web tool may only fetch https pages from these hosts (and their subdomains). */
    @Bean
    fun webPageFetcher(@Value("\${rag.web.allowed-hosts}") allowedHosts: List<String>) = WebPageFetcher(allowedHosts)

    /** F5: allow-listed answer models + prices (rag.eval.models). */
    @Bean
    fun modelCatalog(props: RagProperties) = ModelCatalog(props.eval.models)

    @Bean
    fun chatClient(
        builder: ChatClient.Builder,
        sessionService: SessionService,
        ragAdvisor: RetrievalAugmentationAdvisor,
        props: RagProperties,
    ): ChatClient =
        builder
            // System prompt is config, sent on every request; SessionService refuses to store system messages by default.
            .defaultSystem("You are a concise Kotlin tutor. Prefer short answers with a small code example.")
            // Session advisors run first (order HIGHEST_PRECEDENCE + 1000), so RAG sees the history.
            .defaultAdvisors(
                SessionAdvisors.builder(sessionService)
                    // keep the prompt small: once a session passes N turns, keep only the last few
                    .compaction(
                        TurnCountTrigger(props.maxTurns),
                        WindowCompactionStrategy.builder().lastTurns(props.keepTurns).build(),
                    )
                    .build()
            )
            .defaultAdvisors(ragAdvisor, SimpleLoggerAdvisor())
            .build()
}
