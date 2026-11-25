package dev.havlicek.rag

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** F8: header that says who is calling. Prep for the SaaS backend, where this becomes the authenticated principal. */
const val USER_HEADER = "X-User-Id"

data class AppUser(
    val id: UUID,
    /** what the client sent in [USER_HEADER] (later: the auth provider's subject) */
    val externalId: String,
    val plan: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
)

class InvalidUserIdException(message: String) : RuntimeException(message)

/** F8: user registry: a row in app_user (jdbc) or a map (memory profile). */
interface UserDirectory {
    /** Creates the user on first sight, otherwise bumps last_seen_at. */
    fun touch(externalId: String): AppUser

    fun find(externalId: String): AppUser?

    companion object {
        private val VALID_ID = Regex("[A-Za-z0-9._@-]{1,64}")

        fun requireValidId(externalId: String): String = externalId.also {
            if (!VALID_ID.matches(it)) throw InvalidUserIdException("Invalid $USER_HEADER '$it', expected 1-64 of [A-Za-z0-9._@-]")
        }
    }
}

/** F8: Postgres app_user (V2__app_user.sql); one upsert per request is plenty for a learning app. */
class JdbcUserDirectory(private val jdbc: JdbcClient) : UserDirectory {

    override fun touch(externalId: String): AppUser =
        jdbc.sql(
            """
            INSERT INTO app_user (external_id) VALUES (:externalId)
            ON CONFLICT (external_id) DO UPDATE SET last_seen_at = now()
            RETURNING id, external_id, plan, created_at, last_seen_at
            """.trimIndent()
        )
            .param("externalId", UserDirectory.requireValidId(externalId))
            .query { rs, _ -> rs.toUser() }
            .single()

    override fun find(externalId: String): AppUser? =
        jdbc.sql("SELECT id, external_id, plan, created_at, last_seen_at FROM app_user WHERE external_id = :externalId")
            .param("externalId", externalId)
            .query { rs, _ -> rs.toUser() }
            .optional()
            .orElse(null)

    private fun ResultSet.toUser() = AppUser(
        id = getObject("id", UUID::class.java),
        externalId = getString("external_id"),
        plan = getString("plan"),
        createdAt = getTimestamp("created_at").toInstant(),
        lastSeenAt = getTimestamp("last_seen_at").toInstant(),
    )
}

/** F8: memory profile / unit tests. */
class InMemoryUserDirectory : UserDirectory {
    private val users = ConcurrentHashMap<String, AppUser>()

    override fun touch(externalId: String): AppUser {
        UserDirectory.requireValidId(externalId)
        val now = Instant.now()
        return users.compute(externalId) { _, old -> old?.copy(lastSeenAt = now) ?: AppUser(UUID.randomUUID(), externalId, "free", now, now) }!!
    }

    override fun find(externalId: String): AppUser? = users[externalId]
}
