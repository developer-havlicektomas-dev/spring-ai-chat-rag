package dev.havlicek.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

/**
 * F9: AI Study Guide spike. Extracted note text -> explanation + key concepts + MCQ quiz (+ PRO flashcards), strictly
 * grounded in the note:
 *  1. guard: plan limits (402) + atomic daily quota (429)
 *  2. chunk the note (C1, C2, ... with offsets); long notes -> windows, one structured call each, in parallel
 *  3. ChatClient .responseEntity<StudyDraft>() with OpenAI structured outputs + schema validation
 *  4. validation: every quiz answer needs a verbatim quote that really is in the note, else it is dropped
 *  5. note without study content -> labeled general-knowledge fallback, never a made-up quiz
 * The note itself is the "retrieval": the context is the user's own material, not the Kotlin docs store.
 */
@Service
class StudyGuideService(
    builder: ChatClient.Builder,
    private val guard: StudyGuard,
    private val usageStore: StudyUsageStore,
    private val props: StudyProperties,
    @Value("classpath:prompts/study-system.st") systemPrompt: Resource,
    @Value("classpath:prompts/study-generate.st") private val generatePrompt: Resource,
    @Value("classpath:prompts/study-fallback.st") private val fallbackPrompt: Resource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Own client: none of the Kotlin-docs RAG / session advisors of the main chatClient bean.
    private val chatClient = builder
        .defaultSystem(systemPrompt)
        .defaultOptions(ChatOptions.builder().temperature(props.temperature))
        .build()

    suspend fun generate(userId: String, request: StudyGenerateRequest): StudyGuideResponse {
        val ticket = guard.authorize(userId, request)
        val started = System.nanoTime()
        val result = try {
            build(request)
        } catch (e: Exception) {
            ticket.release() // failed generations don't count against the quota
            throw e
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        runCatching {
            usageStore.record(
                GenerationRecord(
                    ticket.user.id, ticket.plan, request.features, request.text.length, result.chunks, result.quiz.size,
                    result.answerType, result.model, result.inputTokens, result.outputTokens, latencyMs,
                )
            )
        }.onFailure { log.warn("Could not record study generation", it) }
        return result.copy(
            latencyMs = latencyMs,
            usage = StudyUsageInfo(ticket.plan, ticket.used, ticket.limit, ticket.resetsAt),
        )
    }

    private suspend fun build(request: StudyGenerateRequest): StudyGuideResponse {
        val note = request.text
        val chunks = NoteChunker.chunk(note, props.chunkChars)
        val windows = NoteChunker.windows(chunks, props.windowChars)
        val wantQuiz = StudyFeature.QUIZ in request.features
        // ask for ~25% spare questions: some fail validation (quote not in the note), extras are cut after validation
        val asked = if (wantQuiz) request.quizSize + (request.quizSize + 3) / 4 else 0
        val quotas = distribute(asked, windows.map { w -> w.sumOf { it.text.length } })

        val drafts = coroutineScope {
            windows.mapIndexed { i, window ->
                async(Dispatchers.IO) { callWindow(request, window, i, windows.size, quotas[i]) }
            }.awaitAll()
        }
        val tokensIn = drafts.sumOfOrNull { it.second.metadata.usage?.promptTokens }
        val tokensOut = drafts.sumOfOrNull { it.second.metadata.usage?.completionTokens }
        val model = drafts.firstNotNullOfOrNull { it.second.metadata.model?.takeIf(String::isNotBlank) }

        val usable = drafts.map { it.first }.filter { it.sufficient }
        if (usable.isEmpty()) {
            val reason = drafts.map { it.first.insufficientReason }.firstOrNull { it.isNotBlank() } ?: "no study content found"
            return fallback(request, reason, chunks.size, windows.size, tokensIn, tokensOut, model)
        }
        return assemble(request, note, chunks, usable, windows.size, tokensIn, tokensOut, model)
    }

    private fun callWindow(request: StudyGenerateRequest, window: List<NoteChunk>, index: Int, total: Int, quizCount: Int): Pair<StudyDraft, ChatResponse> {
        val tasks = buildList {
            if (StudyFeature.EXPLANATION in request.features || StudyFeature.FLASHCARDS in request.features) {
                add("- overview: a clear explanation of this material for a student (max 180 words${if (total > 1) ", only this part" else ""}).")
                add("- keyConcepts: the 3-8 most important terms/ideas with definitions, each with chunkId + verbatim quote.")
            } else add("- overview: empty string. keyConcepts: empty list.")
            if (quizCount > 0) add("- quiz: exactly $quizCount multiple-choice questions at difficulty ${request.difficulty}.")
            else add("- quiz: empty list.")
        }.joinToString("\n")
        val entity = chatClient.prompt()
            .user {
                it.text(generatePrompt).params(
                    mapOf(
                        "title" to (request.title ?: ""),
                        "language" to languageName(request.language),
                        "part" to if (total > 1) "This is part ${index + 1} of $total of a longer note." else "",
                        "tasks" to tasks,
                        "difficulty" to request.difficulty.name,
                        "chunks" to window.joinToString("\n\n") { c -> "[${c.id}]\n${c.text}" },
                    )
                )
            }
            .call()
            // Spring AI 2.1: provider-native JSON schema (OpenAI structured outputs) + schema validation with retries
            .responseEntity(StudyDraft::class.java) { it.useProviderStructuredOutput().validateSchema() }
        val draft = entity.entity() ?: error("Model returned no study draft")
        return draft to (entity.response() ?: error("Model returned no response"))
    }

    private fun assemble(
        request: StudyGenerateRequest, note: String, chunks: List<NoteChunk>, drafts: List<StudyDraft>, windows: Int,
        tokensIn: Int?, tokensOut: Int?, model: String?,
    ): StudyGuideResponse {
        val byId = chunks.associateBy { it.id }
        val dropped = mutableListOf<DroppedItem>()

        val concepts = drafts.flatMap { it.keyConcepts }
            .distinctBy { it.term.trim().lowercase() }
            .map { c ->
                val span = span(note, byId, c.chunkId, c.quote)
                KeyConcept(c.term.trim(), c.definition.trim(), span, verified = span != null)
            }

        val quiz = drafts.flatMap { it.quiz }
            .mapNotNull { q ->
                val problem = when {
                    q.options.size != 4 -> "expected 4 options, got ${q.options.size}"
                    q.options.map { it.trim().lowercase() }.toSet().size != q.options.size -> "duplicate options"
                    q.correctIndex !in q.options.indices -> "correctIndex ${q.correctIndex} out of range"
                    else -> null
                }
                val span = if (problem == null) span(note, byId, q.chunkId, q.quote) else null
                when {
                    problem != null -> null.also { dropped += DroppedItem(q.question, problem) }
                    span == null -> null.also { dropped += DroppedItem(q.question, "quote not found in the note: \"${q.quote.take(80)}\"") }
                    else -> q to span
                }
            }
            .distinctBy { (q, _) -> q.question.trim().lowercase() }
            .take(if (StudyFeature.QUIZ in request.features) request.quizSize else 0)
            .mapIndexed { i, (q, span) ->
                val (options, correct) = shuffled(q.options.map(String::trim), q.correctIndex, q.question)
                QuizQuestion(i + 1, q.question.trim(), options, correct, q.explanation.trim(), span)
            }

        val requested = if (StudyFeature.QUIZ in request.features) request.quizSize else 0
        val fullyGrounded = quiz.size == requested && concepts.all { it.verified }
        return StudyGuideResponse(
            title = request.title?.takeIf { it.isNotBlank() } ?: drafts.first().title,
            language = request.language,
            answerType = if (fullyGrounded) "grounded" else "partial",
            grounded = true, // everything returned comes from the note (unverified concepts are flagged)
            insufficientReason = null,
            explanation = if (StudyFeature.EXPLANATION in request.features)
                Explanation(drafts.joinToString("\n\n") { it.overview.trim() }.trim(), concepts, basis = "notes") else null,
            quiz = quiz,
            flashcards = if (StudyFeature.FLASHCARDS in request.features)
                concepts.filter { it.verified }.map { Flashcard(it.term, it.definition, it.source) } else null,
            validation = StudyValidation(requested, quiz.size, dropped, concepts.count { !it.verified }),
            chunks = chunks.size, windows = windows,
            usage = PENDING_USAGE, model = model, inputTokens = tokensIn, outputTokens = tokensOut, latencyMs = 0,
        )
    }

    /** Labeled general-knowledge explanation; no quiz, because a quiz must be answerable from the user's material. */
    private fun fallback(
        request: StudyGenerateRequest, reason: String, chunks: Int, windows: Int, tokensIn: Int?, tokensOut: Int?, model: String?,
    ): StudyGuideResponse {
        val response = if (StudyFeature.EXPLANATION in request.features) chatClient.prompt()
            // replaces the default (JSON / grounding) system prompt; live run: the model echoed "sufficient=false ..." otherwise
            .system(FALLBACK_SYSTEM)
            .user {
                it.text(fallbackPrompt).params(
                    mapOf(
                        "reason" to reason, "title" to (request.title ?: "(none)"),
                        "language" to languageName(request.language), "note" to request.text.take(2_000),
                    )
                )
            }
            .call().chatResponse() else null
        val text = response?.result?.output?.text?.trim().orEmpty()
        val requested = if (StudyFeature.QUIZ in request.features) request.quizSize else 0
        return StudyGuideResponse(
            title = request.title ?: "",
            language = request.language,
            answerType = "fallback",
            grounded = false,
            insufficientReason = reason,
            explanation = if (text.isNotEmpty()) Explanation(text, emptyList(), basis = "general_knowledge", label = FALLBACK_LABEL) else null,
            quiz = emptyList(),
            flashcards = if (StudyFeature.FLASHCARDS in request.features) emptyList() else null,
            validation = StudyValidation(requested, 0, emptyList(), 0),
            chunks = chunks, windows = windows, usage = PENDING_USAGE, model = model,
            inputTokens = sum(tokensIn, response?.metadata?.usage?.promptTokens),
            outputTokens = sum(tokensOut, response?.metadata?.usage?.completionTokens),
            latencyMs = 0,
        )
    }

    /** Trust the quote, not the model's chunk id: search the cited chunk first, then the whole note. */
    private fun span(note: String, chunks: Map<String, NoteChunk>, chunkId: String, quote: String): NoteSpan? {
        val chunk = chunks[chunkId.trim().removePrefix("[").removeSuffix("]")]
        val range = chunk?.let { c -> QuoteLocator.locate(c.text, quote)?.let { (it.first + c.start)..(it.last + c.start) } }
            ?: QuoteLocator.locate(note, quote)
            ?: return null
        val owner = chunks.values.firstOrNull { range.first >= it.start && range.first < it.end } ?: return null
        return NoteSpan(owner.id, note.substring(range.first, range.last + 1), range.first, range.last + 1)
    }

    companion object {
        const val FALLBACK_LABEL = "General knowledge - NOT from your notes. Scan or upload more material for a grounded study guide."
        private const val FALLBACK_SYSTEM = "You are a friendly study tutor. Answer in plain prose, no JSON, no field names."
        private val PENDING_USAGE = StudyUsageInfo(Plan.FREE, 0, 0, java.time.Instant.EPOCH)

        /**
         * LLMs love putting the right answer first (live run: 3/3 at index 0). Shuffle server-side, seeded by the
         * question so the same question always gets the same order (stable for caching / regenerating).
         */
        internal fun shuffled(options: List<String>, correctIndex: Int, seed: String): Pair<List<String>, Int> {
            val order = options.indices.shuffled(kotlin.random.Random(seed.hashCode()))
            return order.map { options[it] } to order.indexOf(correctIndex)
        }

        /** Largest-remainder split of [total] questions over windows by text length. */
        internal fun distribute(total: Int, weights: List<Int>): List<Int> {
            val sum = weights.sum().coerceAtLeast(1)
            val exact = weights.map { total.toDouble() * it / sum }
            val base = exact.map { it.toInt() }.toMutableList()
            exact.withIndex().sortedByDescending { it.value - it.value.toInt() }.take(total - base.sum()).forEach { base[it.index]++ }
            return base
        }

        internal fun languageName(code: String) = when (code.lowercase()) {
            "auto" -> "the same language as the note"
            "en" -> "English"
            "cs" -> "Czech"
            "de" -> "German"
            "sk" -> "Slovak"
            else -> "the language with code '$code'"
        }

        private fun sum(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

        private inline fun <T> List<T>.sumOfOrNull(selector: (T) -> Int?): Int? =
            mapNotNull(selector).takeIf { it.isNotEmpty() }?.sum()
    }
}
