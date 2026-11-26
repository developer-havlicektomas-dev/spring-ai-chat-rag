package dev.havlicek.rag

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.session.DefaultSessionService
import org.springframework.ai.session.SessionService
import org.springframework.ai.session.jdbc.JdbcSessionRepository
import org.springframework.ai.session.jdbc.PostgresJdbcSessionRepositoryDialect
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.pgvector.PgVectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.util.UUID

/**
 * F8: real Postgres, no Docker: runs only when the local dev database answers (box: start-postgres.sh,
 * Mac: `docker compose up -d postgres`); otherwise skipped. Everything happens in a throwaway schema.
 * Override with POSTGRES_TEST_URL / POSTGRES_USER / POSTGRES_PASSWORD.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("dev.havlicek.rag.PostgresPersistenceTest#postgresAvailable")
class PostgresPersistenceTest {
    private val schema = "f8_test_" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val dataSource = DriverManagerDataSource("$URL?currentSchema=$schema,public", USER, PASSWORD) // public: pgvector type
    private val jdbc = JdbcClient.create(dataSource)

    /** a "fresh JVM": new repository + service objects over the same tables */
    private fun newSessionService(): SessionService = DefaultSessionService.builder()
        .sessionRepository(
            JdbcSessionRepository.builder()
                .dataSource(dataSource)
                .dialect(PostgresJdbcSessionRepositoryDialect())
                .transactionManager(DataSourceTransactionManager(dataSource))
                .build()
        )
        .build()

    @BeforeAll
    fun migrate() {
        Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true).load().migrate()
    }

    @AfterAll
    fun dropSchema() {
        JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    @Test
    fun `flyway created the session, user and agent memory tables`() {
        val tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = :s")
            .param("s", schema).query(String::class.java).list().map { it!!.lowercase() }.toSet()
        assertEquals(true, tables.containsAll(setOf("ai_session", "ai_session_event", "ai_session_index", "app_user", "agent_memory", "chat_session_overview", "study_usage", "study_generation")), tables.toString())
    }

    @Test
    fun `a chat session and its owner survive a restart`() {
        val before = UserSessions(newSessionService(), JdbcUserDirectory(jdbc))
        val id = before.open("alice", null)
        newSessionService().apply {
            appendMessage(id, UserMessage("My project uses Kotlin 2.3"))
            appendMessage(id, AssistantMessage("Noted."))
        }

        val afterRestart = newSessionService()
        val sessions = UserSessions(afterRestart, JdbcUserDirectory(jdbc))
        assertEquals(listOf("My project uses Kotlin 2.3", "Noted."), afterRestart.getMessages(id).map { it.text })
        assertEquals(listOf(id), sessions.list("alice").map { it.conversationId })
        assertEquals(2, sessions.list("alice").single().messages)
        assertNull(sessions.find("bob", id))
        assertEquals(2L, jdbc.sql("SELECT messages FROM chat_session_overview WHERE conversation_id = :id").param("id", id).query(Long::class.java).single())
    }

    @Test
    fun `users are upserted`() {
        val users = JdbcUserDirectory(jdbc)
        val first = users.touch("carol")
        val again = users.touch("carol")
        assertEquals(first.id, again.id)
        assertEquals("free", again.plan)
        assertEquals(false, again.lastSeenAt.isBefore(first.lastSeenAt))
        assertNull(users.find("nobody"))
    }

    @Test
    fun `F9 plan switch, plan check constraint and atomic daily quota under concurrency`() {
        val users = JdbcUserDirectory(jdbc)
        assertEquals("pro", users.setPlan("dora", "pro").plan)
        assertEquals("pro", users.touch("dora").plan)
        val bad = runCatching { users.setPlan("dora", "platinum") }
        assertEquals(true, bad.isFailure) // V4 CHECK (plan IN ('free','pro'))

        val usage = JdbcStudyUsageStore(jdbc)
        val user = users.touch("erik")
        val day = java.time.LocalDate.of(2026, 11, 26)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val results = (1..20).map { pool.submit<Int?> { usage.tryConsume(user.id, day, 5) } }.map { it.get() }
        pool.shutdown()
        assertEquals((1..5).toList(), results.filterNotNull().sorted()) // exactly 5 winners, never 6
        assertEquals(5, usage.used(user.id, day))
        usage.refund(user.id, day)
        assertEquals(4, usage.used(user.id, day))
        assertEquals(0, usage.used(user.id, day.plusDays(1)))

        usage.record(GenerationRecord(user.id, Plan.FREE, setOf(StudyFeature.QUIZ, StudyFeature.EXPLANATION), 300, 1, 3, "grounded", "gpt-4o-mini", 900, 400, 4200))
        assertEquals("EXPLANATION,QUIZ", jdbc.sql("SELECT features FROM study_generation WHERE user_id = :u").param("u", user.id).query(String::class.java).single())
    }

    @Test
    fun `koog agent memory round-trips through jsonb`() {
        val store = JdbcAgentMemoryStore(jdbc)
        val clock = ai.koog.utils.time.KoogClock.System
        val messages = listOf(
            Message.System("You are a tutor.", RequestMetaInfo.create(clock)),
            Message.User("Kotlin 2.3, 40 min/day", RequestMetaInfo.create(clock)),
            Message.Assistant("Got it.", ResponseMetaInfo.create(clock, inputTokensCount = 10, outputTokensCount = 3)),
        )
        store.save("k-1", messages)
        store.save("k-1", messages) // upsert
        val memory = AgentConversationMemory(JdbcAgentMemoryStore(jdbc)) // "after restart"
        assertEquals(listOf(MemoryMessage("user", "Kotlin 2.3, 40 min/day"), MemoryMessage("assistant", "Got it.")), memory.show("k-1"))
        memory.forget("k-1")
        assertNull(store.load("k-1"))
    }

    @Test
    fun `pgvector store searches with cosine distance and deletes by metadata filter`() {
        val embeddings = KeywordEmbeddingModel()
        val store = PgVectorStore.builder(JdbcTemplate(dataSource), embeddings)
            .schemaName(schema)
            .vectorTableName("test_docs")
            .dimensions(embeddings.dimensions())
            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
            .indexType(PgVectorStore.PgIndexType.HNSW)
            .initializeSchema(true)
            .build()
            .apply { afterPropertiesSet() }
        store.add(SAMPLE_CHUNKS)
        val hits = store.similaritySearch(SearchRequest.builder().query("what is a sealed class").topK(2).build())
        assertEquals("sealed-classes.md", hits.first().metadata["source"])
        assertNotNull(hits.first().score)
        store.delete("source == 'sealed-classes.md'")
        val after = store.similaritySearch(SearchRequest.builder().query("sealed").topK(10).similarityThresholdAll().build())
        assertEquals(3, after.size)
    }

    companion object {
        private val URL = System.getenv("POSTGRES_TEST_URL") ?: "jdbc:postgresql://localhost:5432/rag"
        private val USER = System.getenv("POSTGRES_USER") ?: "rag"
        private val PASSWORD = System.getenv("POSTGRES_PASSWORD") ?: "rag"

        @JvmStatic
        fun postgresAvailable(): Boolean = runCatching {
            DriverManager.setLoginTimeout(2)
            DriverManager.getConnection(URL, USER, PASSWORD).use { it.isValid(2) }
        }.getOrDefault(false)
    }
}
