# spring-ai-kotlin-rag

A small learning project: RAG over Kotlin docs with Spring Boot 4.1.2, Spring AI 2.0.2, Kotlin 2.3.21, OpenAI and Qdrant.

## Prerequisites
- JDK 21
- A running Docker engine (e.g. `colima start`). `bootRun` starts Qdrant from `compose.yaml` via Spring Boot's Docker Compose support (REST/dashboard on 6333, gRPC on 6334; Spring AI uses gRPC) and stops it when the app exits. Data is kept in the `qdrant_storage` volume. Tests skip Docker Compose.
- `export OPENAI_API_KEY=sk-...`. You need it even to start the app, because creating the Qdrant collection makes one embedding call to read the vector size.

Optional overrides: `CHAT_MODEL` (default `gpt-4o-mini`), `QDRANT_HOST`, `QDRANT_GRPC_PORT`.

## Run
```bash
./gradlew build      # runs offline; tests mock the services
./gradlew bootRun
```

## Try it
```bash
curl -X POST localhost:8080/kotlin/docs/ingest
curl "localhost:8080/kotlin/docs/search?q=how%20do%20I%20avoid%20NullPointerException&k=3"
curl -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
     -d '{"question":"When should I use async instead of launch?"}'
# limit retrieval to one doc
curl -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
     -d '{"question":"How do I start a coroutine?","source":"coroutines.md"}'
# stream the answer token by token (Server-Sent Events); -N turns off curl buffering
curl -N -X POST localhost:8080/kotlin/chat/stream -H 'Content-Type: application/json' \
     -d '{"question":"Can a sealed class be a data class?"}'
```
`/kotlin/chat/ask` returns `{"answer": "...", "sources": [{"source": "coroutines.md", "score": 0.81}]}`.

## How it works
- `src/main/resources/docs/*.md`: sample docs. `DocsService.ingest()` reads them with `MarkdownDocumentReader`, splits them with `TokenTextSplitter` and stores them in the Qdrant collection `kotlin-docs`. Running ingest again replaces each file's chunks.
- `AiConfig` builds a `ChatClient` with session advisors (F1), a modular `RetrievalAugmentationAdvisor` (custom prompt in `src/main/resources/prompts/rag-prompt.st`) and `SimpleLoggerAdvisor`. (Days 4-10 used `QuestionAnswerAdvisor`.) `ChatService` reads the retrieved chunks back from the response context and returns them as `sources`.
- `KotlinController` exposes the three endpoints.

## Tuning notes (Day 6)
- `RAG_TOP_K` (default 4): more chunks give more recall but a longer, costlier prompt and more noise. With 4 small docs, 3 to 5 is plenty.
- `RAG_SIMILARITY_THRESHOLD` (default 0.3, tuned against text-embedding-3-small: on-topic best hits scored ~0.40–0.64, off-topic ~0.15–0.22): chunks below this cosine score are dropped. Too high and nothing is retrieved, so the model answers "I don't know based on the loaded docs."; too low and off-topic chunks sneak in. Check real scores with `/kotlin/docs/search` before picking a value.
- `ADVISOR_LOG_LEVEL=DEBUG` logs the full prompt, including the retrieved chunks, which is the fastest way to see why an answer is wrong.
- The prompt template forbids prior knowledge and gives one fixed refusal sentence, so out-of-scope questions are easy to detect in tests and evals.
- `source` is validated against `[A-Za-z0-9_-]+\.md` because it is put into a filter expression string.

Queries to try: an in-scope one ("What does `?.let` do?"), a cross-doc one ("Can a sealed class be a data class?"), and an out-of-scope one ("How do I configure Ktor routing?"), which should get the refusal.

## Streaming notes (Day 7)
- `POST /kotlin/chat/stream` takes the same body as `/kotlin/chat/ask` and returns `text/event-stream`: one `sources` event (as soon as retrieval finishes), then many `token` events like `{"text":" class"}`, then `done`. Failures arrive as an `error` event.
- Tokens are wrapped in JSON on purpose: SSE clients (browser `EventSource`, OkHttp SSE, Ktor) strip one leading space after `data:`, which would glue words together if raw tokens were sent.
- The app stays on Spring MVC; returning a `Flux` makes MVC handle the response asynchronously, so no WebFlux switch is needed. `spring.mvc.async.request-timeout` is 2m so long answers are not cut off.
- Same pipeline as `/ask`: `ChatClient.stream().chatClientResponse()` still runs `QuestionAnswerAdvisor` first, so the retrieved docs are in the context of the streamed chunks.

## Koog bridge (Day 8)
- `koog-spring-ai-v2-starter-model-chat` wraps the Spring AI `OpenAiChatModel` into a Koog `LLMClient` and auto-configures a `PromptExecutor`. Same key, same model config, no second OpenAI client.
- `POST /kotlin/koog/ask` sends a plain prompt (no RAG) through that executor:
  ```bash
  curl -s -X POST localhost:8080/kotlin/koog/ask -H 'Content-Type: application/json' \
    -d '{"question":"What is a sealed class in Kotlin?"}'
  ```
- Compare with `/kotlin/chat/ask`: without retrieval the model answers from memory and can be out of date.
- Koog runs tools itself; Spring AI only gets the tool schemas. That matters for the Day 9 agent.

## Koog agent (Day 9)
`POST /kotlin/agent/ask` runs a Koog `AIAgent` on the bridged `PromptExecutor` with one tool,
`searchKotlinDocs` (`KotlinDocsTools`, a Koog `ToolSet` over the same Qdrant search as `/docs/search`).

- The model decides whether to call the tool, and how often (`maxIterations = 10` caps the loop).
- The response lists `toolCalls`, and each call is logged as `Agent tool call: ...`.
- A new `AIAgent` is built per request because an agent instance holds per-run state; the `ToolRegistry` is shared.

```bash
curl -s -X POST localhost:8080/kotlin/agent/ask -H 'Content-Type: application/json' \
  -d '{"question":"Do subclasses of a sealed class have to be declared inside the sealed class?"}'
curl -s -X POST localhost:8080/kotlin/agent/ask -H 'Content-Type: application/json' \
  -d '{"question":"Hi, who are you?"}'   # no tool call expected
```

## RAG chain vs Koog agent (Day 10)
`scripts/compare-rag-vs-agent.sh` sends the same 5 questions to `/kotlin/chat/ask` (retrieve once, then answer)
and `/kotlin/agent/ask` (the model decides when and how to search). Needs `jq` and the app running with docs ingested.

```bash
scripts/compare-rag-vs-agent.sh            # or pass a base URL
grep 'Agent tool call' app.log             # each tool call is logged by KoogAgentService
```

Results from one run on 2026-11-13 (gpt-4o-mini, top-k 4, threshold 0.3; answers vary run to run):

| # | Question | RAG chain | Koog agent |
|---|----------|-----------|------------|
| 1 | Do sealed subclasses have to be declared inside the sealed class? | Refused ("I don't know") although `sealed-classes.md` was retrieved, 4.5 s | 1 search, correct (same module and package, not nested), 4.4 s |
| 2 | Data class vs sealed class, when to use each? | Good short answer from both docs, 3.8 s | 2 searches (one per topic), longer and more detailed, 13.6 s |
| 3 | Safe call on a nullable String with a default? | `?.` plus `?:`, 2.5 s | 1 search, same answer, 4.7 s |
| 4 | `launch` vs `async`? | Correct with code, 2.9 s | 3 searches (rephrased queries), correct, 7.6 s |
| 5 | Hi, who are you? | Refused (nothing relevant retrieved), 0.7 s | 0 searches, friendly reply, 1.5 s |

Takeaways:
- The RAG chain is cheaper and faster and predictable: always exactly one retrieval and one LLM call.
- The agent writes its own search queries, can search several times (good for comparison questions)
  and skips search for small talk, but costs more LLM round-trips and latency. `maxIterations` caps it.
- The strict refusal prompt of the RAG chain sometimes refuses answerable questions (Q1). The agent's
  reformulated query ("sealed class subclasses location") found the right chunk.
- `KotlinDocsTools` is plain Kotlin and has its own unit test (`KotlinDocsToolsTest`) with no LLM involved.

## Spring AI 2.1: sessions + modular RAG (F1)
Branch `f1-spring-ai-2.1`, Spring AI `2.1.0-RC2` (no 2.1 GA on Maven Central yet; `main` stays on 2.0.2).

- **Multi-turn chat.** `/kotlin/chat/ask` and `/kotlin/chat/stream` accept an optional `conversationId` and always return one (`conversation` SSE event for streams). Omit it to start a new conversation.
  ```bash
  ID=$(curl -s -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
       -d '{"question":"What is a sealed class in Kotlin?"}' | jq -r .conversationId)
  curl -s -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
       -d "{\"question\":\"Can its subclasses live in a different file?\",\"conversationId\":\"$ID\"}" | jq .answer
  curl -s localhost:8080/kotlin/chat/sessions/$ID | jq      # what the model sees next turn
  curl -s -X DELETE localhost:8080/kotlin/chat/sessions/$ID  # forget it
  ```
- **SessionService replaces ChatMemory.** `DefaultSessionService` + `InMemorySessionRepository` (TTL `RAG_SESSION_TTL_MINUTES`, default 60). A session is an append-only event log; `SessionAdvisors` loads the active history before the call and appends the user and assistant messages after. Compaction: past `RAG_MAX_TURNS` (10) turns keep only the last `RAG_KEEP_TURNS` (6). System prompts are not stored (config, sent every request). For persistence swap in `spring-ai-session-jdbc`.
- **QuestionAnswerAdvisor -> RetrievalAugmentationAdvisor.** Three pluggable steps: `CompressionQueryTransformer` rewrites follow-ups into standalone questions (wrapped in `FollowUpQueryTransformer`, which skips the extra LLM call on first turns), `VectorStoreDocumentRetriever` (same topK/threshold/`source` filter), `ContextualQueryAugmenter` (prompt now uses `{context}`; empty retrieval uses `prompts/rag-empty-context.st`, keeping the fixed refusal sentence).
- **Why the rewrite matters:** "Can its subclasses live in a different file?" without history retrieves the wrong chunks and the model says "No"; inside the conversation it is rewritten to "Can subclasses of a sealed class in Kotlin be defined in a different file...?" and the answer is the correct "Yes, same module and package".
- Order: session advisors run first (`HIGHEST_PRECEDENCE + 1000` vs RAG's `0`), so RAG sees the history and the session stores the raw question, not the augmented prompt.

## Tool confirmation (F2, Spring AI 2.1)

`POST /kotlin/ops/ask` is a small "ops assistant" with three Spring AI `@Tool`s in `DocsAdminTools`:
`listDocSources` runs straight away; `deleteDocSource` and `reindexDocs` have `requiresConfirmation = true`.
When the model calls one of those, `ToolCallingAdvisor` pauses (nothing runs) and the answer comes back with
`status: "awaiting_confirmation"` and the pending calls. The paused reply is kept server-side.
`POST /kotlin/ops/confirm` approves or denies; `ToolCallingManager.executeToolCalls(prompt, reply, decisions)`
runs the approved calls (a denied call answers the model with the reason) and the model finishes the turn.

```bash
# 1) ask for something destructive -> paused
curl -s localhost:8080/kotlin/ops/ask -H 'Content-Type: application/json' \
  -d '{"message":"Delete the coroutines docs from the index"}' | tee /tmp/ops.json
# {"conversationId":"...","status":"awaiting_confirmation","pendingToolCalls":[{"id":"call_...","tool":"deleteDocSource","arguments":"{\"source\":\"coroutines.md\"}"}]}

# 2) deny (or "approve":true) -> the model finishes the turn
curl -s localhost:8080/kotlin/ops/confirm -H 'Content-Type: application/json' \
  -d "{\"conversationId\":\"$(jq -r .conversationId /tmp/ops.json)\",\"approve\":false,\"reason\":\"still needed\"}"
```

Rules: a new message while a call is pending -> 409; confirm with nothing pending -> 409; unknown conversation -> 404.
Policy can also be set without touching code: `spring.ai.tools.confirmation.tools=deleteDocSource`
(adds tools; only `spring.ai.tools.confirmation.enabled=false` turns the flag off).
