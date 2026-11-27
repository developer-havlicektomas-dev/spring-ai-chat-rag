package dev.havlicek.rag

import ai.koog.agents.core.agent.entity.createStorageKey
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** F6: the typed result the LLM must fill in (Koog derives a JSON schema from these annotations). */
@Serializable
@SerialName("StudyPack")
@LLMDescription("A short study pack about a Kotlin topic, built only from the searched docs")
data class StudyPack(
    @property:LLMDescription("Answer to the question in 2-5 sentences")
    val answer: String,
    @property:LLMDescription("2-4 short key points a student should remember")
    val keyPoints: List<String>,
    @property:LLMDescription("Doc file names the answer is based on, e.g. sealed-classes.md; empty if none")
    val sources: List<String>,
    @property:LLMDescription("2-3 quiz questions with short answers, testing the key points")
    val quiz: List<QuizItem>,
    @property:LLMDescription("true only if the searched docs actually cover the question")
    val coveredByDocs: Boolean,
)

@Serializable
@SerialName("QuizItem")
data class QuizItem(
    @property:LLMDescription("Quiz question")
    val question: String,
    @property:LLMDescription("Expected short answer")
    val answer: String,
)

/** What the graph returns: the pack plus how we got it (structured parse vs fallback). */
data class StudyRun(
    val pack: StudyPack,
    val structured: Boolean,
    val draft: String,
    val docSources: List<String>,
    val error: String? = null,
)

private val draftKey = createStorageKey<String>("draft")
private val docSourcesKey = createStorageKey<Set<String>>("docSources")
private val sourceTag = Regex("""\[source: ([^\]]+)]""")

/** Source file names that searchKotlinDocs actually returned (the tool tags every chunk with "[source: x]"). */
internal fun sourcesIn(toolOutput: String): Set<String> =
    sourceTag.findAll(toolOutput).map { it.groupValues[1].trim() }.toSet()

internal const val STRUCTURE_INSTRUCTION =
    "Turn your answer above into a study pack. Use only facts from the searchKotlinDocs results in this conversation. " +
        "If the docs did not cover the question, set coveredByDocs=false, say so in the answer and leave sources and quiz empty."

internal val STUDY_PACK_EXAMPLE = StudyPack(
    answer = "A data class is a class whose main purpose is holding data; the compiler generates equals, hashCode, toString, copy and componentN.",
    keyPoints = listOf("Declared with the data keyword", "Primary constructor needs at least one val/var parameter"),
    sources = listOf("data-classes.md"),
    quiz = listOf(QuizItem("Which functions are generated for a data class?", "equals, hashCode, toString, copy, componentN")),
    coveredByDocs = true,
)

/**
 * F6: custom Koog graph strategy (instead of the default single-run loop used by /agent/ask).
 *
 *   start -> research --toolCalls--> runTools -> collectSources -> readResults --toolCalls--> runTools (loop)
 *            research / readResults --text--> keepDraft -> toStudyPack (structured) -> unwrap -> finish
 */
fun kotlinStudyStrategy(fixingModel: LLModel) = strategy<String, StudyRun>("kotlin-study") {
    val research by nodeLLMRequest("research")
    val runTools by nodeExecuteTools("runTools")
    val collectSources by node<ReceivedToolResults, ReceivedToolResults>("collectSources") { results ->
        val found = results.toolResults.flatMap { sourcesIn(it.output) }
        storage.set(docSourcesKey, storage.get(docSourcesKey).orEmpty() + found)
        results
    }
    val readResults by nodeLLMSendToolResults("readResults")
    val keepDraft by node<String, String>("keepDraft") { draft ->
        storage.set(draftKey, draft)
        STRUCTURE_INSTRUCTION // becomes the user message of the structured request
    }
    val toStudyPack by nodeLLMRequestStructured<StudyPack>(
        name = "toStudyPack",
        examples = listOf(STUDY_PACK_EXAMPLE),
        fixingParser = StructureFixingParser(model = fixingModel, retries = 2),
    )
    val unwrap by node<Result<StructuredResponse<StudyPack>>, StudyRun>("unwrap") { result ->
        val draft = storage.get(draftKey).orEmpty()
        val docSources = storage.get(docSourcesKey).orEmpty().sorted()
        result.fold(
            onSuccess = { StudyRun(it.data, structured = true, draft = draft, docSources = docSources) },
            onFailure = { StudyRun(fallbackPack(draft, docSources), structured = false, draft, docSources, it.message) },
        )
    }

    edge(nodeStart forwardTo research)
    edge(research forwardTo runTools onToolCalls { true })
    edge(research forwardTo keepDraft onTextMessage { true })
    edge(runTools forwardTo collectSources)
    edge(collectSources forwardTo readResults)
    edge(readResults forwardTo runTools onToolCalls { true })
    edge(readResults forwardTo keepDraft onTextMessage { true })
    edge(keepDraft forwardTo toStudyPack)
    edge(toStudyPack forwardTo unwrap)
    edge(unwrap forwardTo nodeFinish)
}

/** If the structured step fails even after the fixing parser, still return the plain draft answer. */
internal fun fallbackPack(draft: String, docSources: List<String>) =
    StudyPack(answer = draft, keyPoints = emptyList(), sources = docSources, quiz = emptyList(), coveredByDocs = docSources.isNotEmpty())
