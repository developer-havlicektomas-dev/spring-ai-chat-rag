package dev.havlicek.rag

import org.springframework.web.bind.annotation.*

data class AskRequest(val question: String)
data class AskResponse(val answer: String)

@RestController
@RequestMapping("/kotlin")
class KotlinController(
    private val docsService: DocsService,
    private val chatService: ChatService,
) {
    @PostMapping("/docs/ingest")
    fun ingest(): Map<String, Int> = docsService.ingest()

    @GetMapping("/docs/search")
    fun search(@RequestParam q: String, @RequestParam(defaultValue = "4") k: Int): List<SearchHit> =
        docsService.search(q, k)

    @PostMapping("/chat/ask")
    fun ask(@RequestBody request: AskRequest): AskResponse =
        AskResponse(chatService.ask(request.question))
}
