package dev.havlicek.rag

import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer
import org.slf4j.LoggerFactory

/**
 * Only pays for the extra "rewrite into a standalone question" LLM call when there is earlier conversation.
 * First turns go straight to retrieval.
 */
class FollowUpQueryTransformer(private val delegate: QueryTransformer) : QueryTransformer {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun transform(query: Query): Query {
        // history = the prompt messages: system + earlier turns + the current user message
        val earlierTurns = query.history().count { it.messageType == MessageType.ASSISTANT }
        if (earlierTurns == 0) return query
        return delegate.transform(query).also { log.info("Rewrote follow-up '{}' -> '{}'", query.text(), it.text()) }
    }
}
