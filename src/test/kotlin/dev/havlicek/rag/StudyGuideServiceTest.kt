package dev.havlicek.rag

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.core.io.ClassPathResource
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F9: the whole generation pipeline with a scripted ChatModel (no OpenAI): grounding checks, fallback, quota refund. */
class StudyGuideServiceTest {
    private val note = """
        Photosynthesis happens in the chloroplasts of plant cells.
        The light-dependent reactions take place in the thylakoid membranes and produce ATP and NADPH.

        The Calvin cycle runs in the stroma and uses ATP and NADPH to fix carbon dioxide into glucose.
    """.trimIndent()

    private val prompts = Collections.synchronizedList(mutableListOf<String>())
    /** answers keyed by what the prompt asks for; default = a good draft */
    private var reply: (String) -> String = { GOOD_DRAFT }

    private val chatModel = object : ChatModel {
        override fun call(prompt: Prompt): ChatResponse {
            val text = prompt.instructions.joinToString("\n") { it.text.orEmpty() }
            prompts += text
            return ChatResponse(
                listOf(Generation(AssistantMessage(reply(text)))),
                ChatResponseMetadata.builder().model("scripted-model").usage(DefaultUsage(100, 50)).build(),
            )
        }
    }

    private val users = InMemoryUserDirectory()
    private val usage = InMemoryStudyUsageStore()
    private val props = StudyProperties()
    private val service = StudyGuideService(
        ChatClient.builder(chatModel), StudyGuard(users, usage, props), usage, props,
        ClassPathResource("prompts/study-system.st"), ClassPathResource("prompts/study-generate.st"), ClassPathResource("prompts/study-fallback.st"),
    )

    private fun generate(user: String = "alice", request: StudyGenerateRequest = StudyGenerateRequest(text = note, title = "Photosynthesis", quizSize = 2)) =
        runBlocking { service.generate(user, request) }

    @Test
    fun `grounded study guide with verified quotes and offsets`() {
        val result = generate()
        assertEquals("grounded", result.answerType)
        assertTrue(result.grounded)
        assertEquals(2, result.quiz.size)
        val q = result.quiz.first()
        assertEquals("stroma", q.options[q.correctIndex])
        assertEquals(note.substring(q.source.start, q.source.end), q.source.quote)
        assertEquals("C1", q.source.chunkId)
        assertEquals("notes", result.explanation!!.basis)
        assertTrue(result.explanation!!.keyConcepts.all { it.verified })
        assertNull(result.flashcards) // not requested
        assertEquals(StudyUsageInfo(Plan.FREE, 1, 3, result.usage.resetsAt), result.usage)
        assertEquals(100, result.inputTokens)
        assertEquals("scripted-model", result.model)
        assertEquals("grounded", usage.generations.single().answerType)
        // the prompt carries the chunk ids, the note and the task
        assertTrue(prompts.single().contains("[C1]") && prompts.single().contains("exactly 3 multiple-choice questions"))
        assertTrue(prompts.single().contains("Use ONLY the note chunks"))
    }

    @Test
    fun `questions whose quote is not in the note or are malformed are dropped`() {
        reply = { GOOD_DRAFT.replace("membranes and produce ATP and NADPH\"", "membranes and produce moonlight\"").replace("\"correctIndex\": 0", "\"correctIndex\": 7") }
        val result = generate()
        assertEquals("partial", result.answerType)
        assertEquals(0, result.quiz.size)
        assertEquals(2, result.validation.droppedQuestions.size)
        assertTrue(result.validation.droppedQuestions.any { it.reason.startsWith("quote not found") })
        assertTrue(result.validation.droppedQuestions.any { it.reason.contains("out of range") })
    }

    @Test
    fun `insufficient note gives a labeled general-knowledge fallback and no quiz`() {
        reply = { prompt ->
            if (prompt.contains("did not contain enough study content")) "Photosynthesis is how plants turn light into chemical energy."
            else """{"sufficient": false, "insufficientReason": "only a shopping list", "title": "", "overview": "", "keyConcepts": [], "quiz": []}"""
        }
        val result = generate(request = StudyGenerateRequest(text = "milk, eggs, bread", title = "Photosynthesis"))
        assertEquals("fallback", result.answerType)
        assertEquals(false, result.grounded)
        assertEquals("only a shopping list", result.insufficientReason)
        assertEquals("general_knowledge", result.explanation!!.basis)
        assertEquals(StudyGuideService.FALLBACK_LABEL, result.explanation!!.label)
        assertTrue(result.quiz.isEmpty())
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("friendly study tutor") && !prompts[1].contains("sufficient=false")) // own system prompt
        assertEquals(200, result.inputTokens) // both calls counted
    }

    @Test
    fun `pro flashcards come from verified key concepts`() {
        users.setPlan("pro", "pro")
        val result = generate("pro", StudyGenerateRequest(text = note, features = setOf(StudyFeature.FLASHCARDS)))
        val cards = assertNotNull(result.flashcards)
        assertEquals(listOf("Calvin cycle", "Thylakoid"), cards.map { it.front })
        assertNull(result.explanation)
        assertTrue(result.quiz.isEmpty())
        assertTrue(prompts.single().contains("quiz: empty list"))
    }

    @Test
    fun `a failing model call gives the quota slot back`() {
        reply = { error("OpenAI is down") }
        assertThrows<IllegalStateException> { generate() }
        assertEquals(0, StudyGuard(users, usage, props).usage("alice").usedToday)
    }

    @Test
    fun `long notes are split into windows with the quiz spread over them`() {
        users.setPlan("pro", "pro")
        val long = (1..12).joinToString("\n\n") { "Section $it. " + "The Calvin cycle runs in the stroma and uses ATP and NADPH to fix carbon dioxide into glucose. ".repeat(20) }
        val result = generate("pro", StudyGenerateRequest(text = long, quizSize = 4))
        assertTrue(result.windows > 1, "windows=${result.windows}")
        assertEquals(result.windows, prompts.size)
        assertEquals(5, prompts.sumOf { Regex("exactly (\\d+) multiple-choice").find(it)?.groupValues?.get(1)?.toInt() ?: 0 })
        assertTrue(prompts.all { it.contains("This is part") })
        assertEquals(result.windows * 100, result.inputTokens)
    }

    companion object {
        val GOOD_DRAFT = """
            {
              "sufficient": true, "insufficientReason": "", "title": "Photosynthesis",
              "overview": "Plants make glucose in two stages.",
              "keyConcepts": [
                {"term": "Calvin cycle", "definition": "Fixes CO2 into glucose in the stroma.", "chunkId": "C1",
                 "quote": "The Calvin cycle runs in the stroma"},
                {"term": "Thylakoid", "definition": "Membrane where light reactions happen.", "chunkId": "C1",
                 "quote": "light-dependent reactions take place in the thylakoid membranes"}
              ],
              "quiz": [
                {"question": "Where does the Calvin cycle run?", "options": ["stroma", "thylakoid", "nucleus", "cell wall"],
                 "correctIndex": 0, "explanation": "The note says it runs in the stroma.", "chunkId": "C1",
                 "quote": "The Calvin cycle runs in the stroma and uses ATP and NADPH to fix carbon dioxide"},
                {"question": "What do the light-dependent reactions produce?", "options": ["glucose", "ATP and NADPH", "oxygen only", "DNA"],
                 "correctIndex": 1, "explanation": "They produce ATP and NADPH.", "chunkId": "C2",
                 "quote": "take place in the thylakoid membranes and produce ATP and NADPH"}
              ]
            }
        """.trimIndent()
    }
}
