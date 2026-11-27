package dev.havlicek.rag

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** F9: what the client wants generated from the note. FLASHCARDS is PRO-only in the default config. */
enum class StudyFeature { EXPLANATION, QUIZ, FLASHCARDS }

enum class Difficulty { EASY, MEDIUM, HARD }

/** F9: POST /kotlin/study/generate. [text] = text the app extracted from the scan / PDF / slide deck. */
data class StudyGenerateRequest(
    @field:NotBlank @field:Size(max = 100_000) val text: String,
    @field:Size(max = 200) val title: String? = null,
    /** output language (BCP 47, e.g. "en", "cs"); "auto" = same language as the note */
    @field:Pattern(regexp = "auto|[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?") val language: String = "auto",
    @field:Min(1) @field:Max(50) val quizSize: Int = 5,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    @field:NotEmpty val features: Set<StudyFeature> = setOf(StudyFeature.EXPLANATION, StudyFeature.QUIZ),
)

// ---- what the LLM returns (one call per window); JSON schema generated from these classes ----

data class DraftConcept(
    @get:JsonPropertyDescription("Short term or name from the note") val term: String,
    @get:JsonPropertyDescription("1-2 sentence definition using only the note") val definition: String,
    @get:JsonPropertyDescription("Id of the chunk the definition comes from, e.g. C2") val chunkId: String,
    @get:JsonPropertyDescription("Exact sentence or phrase copied verbatim from that chunk that supports the definition") val quote: String,
)

data class DraftQuestion(
    val question: String,
    @get:JsonPropertyDescription("Exactly 4 answer options, without letters or numbers") val options: List<String>,
    @get:JsonPropertyDescription("0-based index of the correct option") val correctIndex: Int,
    @get:JsonPropertyDescription("Why the correct option is right, based on the note") val explanation: String,
    @get:JsonPropertyDescription("Id of the chunk that proves the answer, e.g. C1") val chunkId: String,
    @get:JsonPropertyDescription("Exact text copied verbatim from that chunk that proves the correct answer") val quote: String,
)

data class StudyDraft(
    @get:JsonPropertyDescription("true if the note contains enough study content to explain and quiz on") val sufficient: Boolean,
    @get:JsonPropertyDescription("Why the note is not usable (empty string when sufficient)") val insufficientReason: String,
    @get:JsonPropertyDescription("Short title of the material") val title: String,
    @get:JsonPropertyDescription("Clear explanation of the material for a student, only from the note (empty if not requested)") val overview: String,
    val keyConcepts: List<DraftConcept>,
    val quiz: List<DraftQuestion>,
)

// ---- API response ----

/** Where in the note something comes from; [start]/[end] are character offsets into the request text. */
data class NoteSpan(val chunkId: String, val quote: String, val start: Int, val end: Int)

data class KeyConcept(val term: String, val definition: String, val source: NoteSpan?, val verified: Boolean)

/** [basis] "notes" = generated from the note; "general_knowledge" = labeled fallback, NOT from the user's material. */
data class Explanation(val overview: String, val keyConcepts: List<KeyConcept>, val basis: String, val label: String? = null)

data class QuizQuestion(
    val id: Int,
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    val source: NoteSpan,
)

data class Flashcard(val front: String, val back: String, val source: NoteSpan?)

data class DroppedItem(val text: String, val reason: String)

/** F9 validation step: every quiz answer must be backed by a verbatim quote that really is in the note. */
data class StudyValidation(
    val questionsRequested: Int,
    val questionsReturned: Int,
    val droppedQuestions: List<DroppedItem>,
    val unverifiedConcepts: Int,
)

data class StudyUsageInfo(val plan: Plan, val usedToday: Int, val dailyLimit: Int, val resetsAt: java.time.Instant)

data class StudyGuideResponse(
    val title: String,
    val language: String,
    /** grounded = everything from the note; partial = from the note but fewer verified items than asked; fallback = note insufficient */
    val answerType: String,
    val grounded: Boolean,
    val insufficientReason: String?,
    val explanation: Explanation?,
    val quiz: List<QuizQuestion>,
    val flashcards: List<Flashcard>?,
    val validation: StudyValidation,
    val chunks: Int,
    val windows: Int,
    val usage: StudyUsageInfo,
    val model: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long,
)
