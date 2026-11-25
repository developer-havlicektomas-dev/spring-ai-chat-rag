package dev.havlicek.rag

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.core.io.ClassPathResource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** F8: queries to run against both stores; default = every question of the golden set (evals/golden-qa.json). */
data class CompareStoresRequest(
    @field:Size(max = 50) val queries: List<@Size(min = 1, max = 500) String>? = null,
    @field:Min(1) @field:Max(20) val topK: Int = 4,
    @field:Min(1) @field:Max(50) val repeats: Int = 5,
)

data class StoreStatus(val store: StoreKind, val active: Boolean, val chunks: Int?, val error: String? = null)

/** F8: Qdrant vs pgvector side by side. The active store (rag.vector-store) is the one RAG uses. */
@RestController
@RequestMapping("/kotlin/vectorstores")
class VectorStoreController(
    private val registry: VectorStoreRegistry,
    private val comparator: VectorStoreComparator,
    private val docsService: DocsService,
) {
    private val goldenQuestions by lazy { ClassPathResource("evals/golden-qa.json").inputStream.use(GoldenSet::load).map { it.question } }

    /** Which store is active and how many chunks each holds (an error if a store is unreachable). */
    @GetMapping
    fun status(): List<StoreStatus> = StoreKind.entries.map { kind ->
        runCatching { StoreStatus(kind, kind == registry.activeKind, docsService.countAll(registry.store(kind))) }
            .getOrElse { StoreStatus(kind, kind == registry.activeKind, null, it.message ?: it.toString()) }
    }

    /** Ingest the bundled docs into one store (same chunking as /kotlin/docs/ingest), e.g. the inactive one before comparing. */
    @PostMapping("/{store}/ingest")
    fun ingest(@PathVariable store: String): Map<String, Int> =
        docsService.ingest(store = registry.store(StoreKind.of(store)))

    @PostMapping("/compare")
    fun compare(@Valid @RequestBody(required = false) request: CompareStoresRequest?): StoreComparisonReport {
        val r = request ?: CompareStoresRequest()
        return comparator.compare(r.queries?.map(String::trim) ?: goldenQuestions, r.topK, r.repeats)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
