package dev.havlicek.rag

import org.slf4j.LoggerFactory
import org.springframework.ai.session.InMemorySessionRepository
import org.springframework.ai.session.SessionRepository
import org.springframework.ai.session.SessionService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * F8: rag.persistence=jdbc (default) -> Postgres: Spring AI's auto-configured JdbcSessionRepository (it backs off as
 * soon as we define a SessionRepository ourselves), app_user rows and Koog agent memory in agent_memory.
 * rag.persistence=memory (profile "memory") -> the F1-F7 in-memory variants, no DataSource at all.
 */
@Configuration
class PersistenceConfig {

    @Configuration
    @ConditionalOnProperty("rag.persistence", havingValue = "jdbc", matchIfMissing = true)
    class Jdbc {
        @Bean
        fun userDirectory(jdbc: JdbcClient): UserDirectory = JdbcUserDirectory(jdbc)

        @Bean
        fun agentMemoryStore(jdbc: JdbcClient): AgentMemoryStore = JdbcAgentMemoryStore(jdbc)
    }

    @Configuration
    @ConditionalOnProperty("rag.persistence", havingValue = "memory")
    class Memory {
        @Bean
        fun sessionRepository(): SessionRepository = InMemorySessionRepository.builder().build()

        @Bean
        fun userDirectory(): UserDirectory = InMemoryUserDirectory()

        @Bean
        fun agentMemoryStore(): AgentMemoryStore = InMemoryAgentMemoryStore()
    }
}

/** F8: persisted sessions never get evicted by a JVM restart any more, so expired ones are deleted on a schedule. */
@Component
@EnableScheduling
class ExpiredSessionCleaner(private val sessionService: SessionService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "\${rag.session-cleanup-interval:PT1H}")
    fun deleteExpired() {
        val deleted = sessionService.deleteExpiredSessions(Instant.now())
        if (deleted > 0) log.info("Deleted {} expired chat sessions", deleted)
    }
}
