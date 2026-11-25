# spring-ai-kotlin-rag

A small learning project: RAG over Kotlin docs with Spring Boot 4.1.2, Spring AI 2.1.0, Kotlin 2.3.21, Koog 1.3.0, OpenAI, Qdrant and (F8) Postgres + pgvector.

## Prerequisites
- JDK 21
- A running Docker engine (e.g. `colima start`). `bootRun` starts Qdrant from `compose.yaml` via Spring Boot's Docker Compose support (REST/dashboard on 6333, gRPC on 6334; Spring AI uses gRPC) and stops it when the app exits. Data is kept in the `qdrant_storage` volume. Tests skip Docker Compose.
  F8: `compose.yaml` also starts Postgres 17 + pgvector (`postgres_data` volume); the app needs it by default (sessions, users, agent memory). Without Postgres: `SPRING_PROFILES_ACTIVE=memory`, see [Postgres + pgvector (F8)](#postgres--pgvector-f8).
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

## F3 Better retrieval (Spring AI 2.1)

Same branch, still Spring AI `2.1.0-RC2` (no 2.1 GA on Maven Central on 2026-11-18). Pipeline inside `RetrievalAugmentationAdvisor`:

```
follow-up rewrite -> Qdrant (topK, threshold, metadata filter)
  -> [decision-filter] -> [rerank] -> [sufficiency-gate]          (DocumentPostProcessors, each switchable)
  -> numbered context [1]..[n]  or  general-knowledge fallback prompt
```

**Config flags** (`application.yml`, env vars in brackets):

| Property | Default | What it does |
|---|---|---|
| `rag.chunk-size` (`RAG_CHUNK_SIZE`) | 800 | `TokenTextSplitter` chunk size in tokens |
| `rag.chunk-overlap` (`RAG_CHUNK_OVERLAP`) | 0 | tokens shared by neighbouring chunks (`withChunkOverlap`, new in 2.1) |
| `rag.rerank.enabled` / `top-n` / `min-score` | false / 3 / 0.1 | `DecisionDocumentReranker`: one yes/no "does this passage answer the query?" per chunk, sorted by probability |
| `rag.decision-filter.enabled` / `fail-closed` | false / false | `DecisionDocumentFilter`: injection, contradiction, relevance, answer-evidence questions per chunk |
| `rag.sufficiency.enabled` / `min-probability` | true / 0.5 | one yes/no "is the context enough?" over all kept chunks; no -> labeled fallback. `false` = old hard refusal |
| `rag.decision-timeout-seconds` | 20 | timeout per decision batch |
| `spring.ai.openai.decision.model` | `gpt-6-luna` (Spring AI default) | model behind the auto-configured `OpenAiDecisionModel` (OpenAI Decisions API) |

`rerank` and `decisionFilter` can also be set per request (they override the config).

**Request / response.** `/kotlin/chat/ask` (and `/stream`) accept `sources` (list of file names), `topics` (list; set at ingest
from `DocsService.TOPICS`: `concurrency`, `classes`, `type-system`), the old `source`, `rerank`, `decisionFilter`. Filters
are AND-ed and built with `FilterExpressionBuilder` (`source IN [..] AND topic IN [..]`); bad values -> 400 with a message.
The response adds `answerType` (`grounded` | `general_knowledge_fallback` | `no_context`), `citations`
(`n`, source, topic, chunkIndex, chunkId, vector score, rerankScore, classification, snippet, `cited`), `sufficiency`
and `pipeline` (per step: in/out count, ms, surviving chunks). The stream sends `answerType` and `citations` events after `sources`.

```bash
# grounded, with [n] citations
curl -s localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
  -d '{"question":"What is the difference between launch and async?"}' | jq '{answer, answerType, citations}'
# metadata filter
curl -s localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
  -d '{"question":"How do I create a copy with one property changed?","topics":["classes"]}' | jq .
# off-topic -> labeled fallback
curl -s localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
  -d '{"question":"What is the capital of Australia?"}' | jq '{answer, answerType}'
# decision steps on for one request
curl -s localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
  -d '{"question":"Can subclasses of a sealed class be declared in another file?","rerank":true,"decisionFilter":true}' | jq .pipeline
# chunk experiment (re-ingests, then restores the configured settings)
scripts/chunk-experiment.sh
```

**Results from the runtime test (2026-11-18, gpt-4o-mini, topK 4, threshold 0.3):**

- Grounded: "launch vs async" -> answer with `[1]` markers, 1 citation (`coroutines.md#1`, score 0.451, cited), sufficiency 1.0, gate 307 ms, 3.8 s total.
- Filter `topics:["classes"]` "copy with one property changed" -> `data-classes.md#1`, cited `[1]`, 1.6 s. Same question with `sources:["coroutines.md"]` -> nothing passes the threshold -> `general_knowledge_fallback`.
- Off-topic "capital of Australia" -> `[General knowledge - not from your docs] The capital of Australia is Canberra.` (0.8 s; nothing retrieved, the gate did not need to run).
- Gate doing the work: "How does debounce work on a Kotlin Flow?" retrieves 1 coroutines chunk, gate says 0.0 -> labeled fallback. With `RAG_SUFFICIENCY_ENABLED=false` the same question got an uncited answer from prior knowledge despite the "only use the sources" rule, and the off-topic one got `no_context` + the refusal sentence.
- Rerank / filter on vs off (whole request latency, `pipeline` from the response):

| Question | Off | Rerank | Filter | Filter + rerank |
|---|---|---|---|---|
| "Can subclasses of a sealed class be declared in another file?" | 4 chunks, 2.11 s | 1 kept: `sealed#0` rerank=0.97 (180 ms), 2.18 s | 2 kept: `sealed#0` INCLUDED, `data#3` CONFLICTING (143 ms), 1.93 s | `sealed#0` only (233+138 ms), 2.16 s |
| "Difference between a data class and a sealed class? When to use each?" | 4 chunks (2 sealed, 2 data), grounded, 4.87 s | kept `sealed#2` 0.30, `sealed#0` 0.19 -> gate 0.03 -> fallback, 3.30 s | kept only `data#3` -> gate 0.02 -> fallback, 2.83 s | `data#3` -> rerank drops it -> fallback, 2.80 s |

  Take-away: per-passage decisions are great for single-fact questions (noise gone, fewer prompt tokens) but hurt
  comparison questions, where each chunk answers only half. Hence both are off by default; F4 will measure it properly.
  Each decision step added roughly 100-350 ms here (parallel calls, 4 at a time).

**Chunk experiment** (`scripts/chunk-experiment.sh`, `/docs/search` k=3; `top1` = source#chunk and cosine score):

| chunkSize:overlap | chunks | launch vs async | null default | sealed in other file | data class copy |
|---|---|---|---|---|---|
| 800:0 | 15 | coroutines#1 0.450 | null-safety#1 0.326 | sealed#0 0.580 | data#1 0.466 |
| 64:16 | 15 | coroutines#1 0.447 | null-safety#1 0.316 | sealed#0 0.581 | data#1 0.469 |
| 32:8 | 24 | coroutines#1 0.482 | null-safety#2 0.336 | sealed#0 0.581 | data#2 0.479 |
| 16:0 | 40 | coroutines#3 0.416 | null-safety#1 0.317 | sealed#0 0.585 | data#4 0.483 |

- `MarkdownDocumentReader` already splits per heading, and these docs have ~25-60 token sections, so 800 vs 64 changes
  nothing (same 15 chunks, score differences are embedding noise).
- 32:8 gave the best top-1 scores and a bigger gap to #2 for "launch vs async" (0.482 vs 0.367).
- 16:0 fragments sentences (avg hit 50-80 chars): scores bunch together (0.416 vs 0.414 for launch/async), so top-k
  gets less selective and citations lose context. Higher similarity score alone does not mean a better answer.
- Note: `withMinChunkSizeChars` defaults to 350 chars, which would hide small chunk sizes; `DocsService` lowers it.

**What exists in the 2.1.0-RC2 jars** (checked before use): `org.springframework.ai.decision.DecisionModel` (+ `DecisionPrompt`,
`DecisionResponse`, `YesNoQuestion`/`ScoreQuestion`/`ChoiceQuestion`, `FieldsInput`), `org.springframework.ai.openai.OpenAiDecisionModel`
(auto-configured by `OpenAiDecisionAutoConfiguration`), `org.springframework.ai.rag.postretrieval.document.DecisionDocumentReranker`
and `DecisionDocumentFilter`. The sufficiency gate is our own small `DocumentPostProcessor` on the same `DecisionModel`.

**Gotchas found on the way**
- Post-processors receive the *original* user text, not the rewritten follow-up. `FollowUpQueryTransformer` stores the
  rewrite in the request context and `PipelineStep` hands that to the decision steps ("its subclasses" is unjudgeable alone).
- `ContextualQueryAugmenter` renders the empty-context template without `{query}`, so a fallback answer needs a wrapper
  (`GroundedOrFallbackAugmenter`).
- `DecisionDocumentFilter` keeps `CONFLICTING` chunks (marked in `classification`); only `EXCLUDED` ones are dropped.
- Generation is still gpt-4o-mini: for "can sealed subclasses live in another file?" it now answers "No" from the doc's
  "same module and package" sentence (the doc never mentions files). That's a model/doc gap, not retrieval; F4 evals should catch it.

## F4 RAG evals (Spring AI 2.1)

Golden set: `src/main/resources/evals/golden-qa.json` (18 questions: 15 answerable from `docs/`, 3 out of scope that must
get the labeled general-knowledge fallback). Each question runs through the real `/chat/ask` pipeline per variant
(`baseline` = decision steps off, `rerank`, `filter`, `full` = filter + reranker) and is scored by LLM judges:

| column | how |
|---|---|
| routing | in scope -> `grounded`, out of scope -> `general_knowledge_fallback` |
| retrieval hit | an expected source is among the chunks the model saw |
| relevancy | `RelevancyEvaluator(question, context, answer)` |
| grounded | `FactCheckingEvaluator(context -> answer)`: every claim backed by the chunks |
| correct | `FactCheckingEvaluator(answer -> reference)`: the answer contains the reference facts |
| decision ms / tokens / $ | from the `pipeline` trace and the answer call's token usage (decision + judge calls not priced) |

```bash
SPRING_DOCKER_COMPOSE_ENABLED=false ./gradlew evalRag                      # baseline vs full, prints table
./gradlew evalRag -PevalVariants=baseline,rerank,filter,full -PevalLimit=5   # on the Mac compose starts Qdrant
EVAL_JUDGE_MODEL=gpt-4.1 ./gradlew evalRag                                   # stronger judge than the answer model
curl -s -X POST localhost:8080/kotlin/evals/run -H 'Content-Type: application/json' \
  -d '{"variants":["baseline","full"],"ids":["mix-result","oos-chitchat"]}' | jq .summaries
```

First run (2026-11-19, gpt-4o-mini answers and judge):

| variant | routing | retrieval hit | relevancy | grounded | correct | avg ms | p95 ms | decision ms | tokens in/out |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 100% | 100% | 100% | 100% | 100% | 1918 | 3041 | 132 | 309/101 |
| full | 100% | 100% | 100% | 100% | 100% | 2320 | 3914 | 477 | 236/100 |

Takeaway: on 4 tiny docs, quality is at the ceiling either way. The decision steps cost about +400 ms per question and
remove off-topic chunks (24% fewer prompt tokens, cleaner sources), but they don't improve answers here. To make the
comparison mean something, add harder questions and more (and overlapping) docs. Sanity check: changing one reference to
a wrong fact flips `correct` to N, so the judge isn't just saying yes to everything.

## F5 Model comparison (Spring AI 2.1.0 GA)

Spring AI 2.1.0 GA hit Maven Central on 2026-11-19; this branch now uses it (RC2 -> GA needed no code changes).

The answer call can switch model per request (`OpenAiChatOptions` override merged by ChatClient); query rewrite,
decision steps and the **judge (gpt-4.1, fixed)** keep their models, so quality scores are comparable. Allowed models
and their prices live in `rag.eval.models` (application.yml, OpenAI pricing page, 2026-11-20); anything else is a 400.
Reasoning models (gpt-5*, o*) never get a temperature (they reject it). Cost = real prompt + completion tokens of each
answer call x list price (unknown price -> "unknown", never a guess). Only OpenAI models: it's the only key on the box.

The golden set grew to 28 cases: 10 new `difficulty: hard` ones (sealed subclass in another file / module trap,
launch vs async comparison, runBlocking in a handler, destructuring a body property, constructor rules, smart casts,
`?.let ?:` semantics, near-miss out-of-scope SupervisorJob / Flow timeout).

```bash
SPRING_DOCKER_COMPOSE_ENABLED=false ./gradlew evalRag -PevalModels=gpt-4o-mini,gpt-4.1-mini,gpt-5.4-mini,gpt-5.6-luna
./gradlew evalRag -PevalModels=gpt-4o-mini,gpt-5.4-nano -PevalDifficulty=hard   # only the hard cases
# -> build/reports/rag-eval/model-comparison.md (+ per-case rows in rag-eval.md)
curl -s -X POST localhost:8080/kotlin/evals/run -H 'Content-Type: application/json' \
  -d '{"models":["gpt-4o-mini","gpt-5.4-nano"],"difficulty":"hard","limit":3}' | jq -r .comparison
curl -s -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' \
  -d '{"question":"Can a sealed subclass live in another file?","model":"gpt-4.1-mini"}' | jq '{model, usage, answer}'
# token usage per model via Micrometer (Spring AI observations)
curl -s 'localhost:8080/actuator/metrics/gen_ai.client.token.usage?tag=gen_ai.request.model:gpt-4.1-mini&tag=gen_ai.token.type:output'
```

Run on 2026-11-20 (28 cases, baseline variant, judge gpt-4.1, Qdrant + 4 docs):

| model | variant | n | routing | relevancy | grounded | correct | correct (hard) | p50 ms | p95 ms | avg tokens in/out | $ per 1k questions | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gpt-4o-mini | baseline | 28 | 96% | 96% | 91% | 83% | 63% | 1945 | 3999 | 299/108 | 0.110 | 0 |
| gpt-4.1-mini | baseline | 28 | 96% | 96% | 91% | 100% | 100% | 1524 | 1983 | 299/102 | 0.284 | 0 |
| gpt-5.4-mini | baseline | 28 | 96% | 96% | 96% | 96% | 100% | 1261 | 1945 | 298/68 | 0.531 | 0 |
| gpt-5.6-luna | baseline | 28 | 96% | 96% | 91% | 96% | 100% | 2530 | 4795 | 298/178 | 0.273 | 0 |
Takeaways:
- The hard cases separate the models: gpt-4o-mini drops to 63% on them (it falls for "sealed subclasses must be in the
  same file" - old Kotlin knowledge beats the retrieved chunk), the three newer models get 100%.
- gpt-5.4-mini is the fastest and most grounded but ~5x gpt-4o-mini's cost; gpt-4.1-mini is the best value (100%
  correct, ~2.6x cost); gpt-5.6-luna is cheap per token but writes longer answers (178 output tokens) and is the slowest.
- routing 96% for every model is one case (`hard-dc-destructure-body`): the sufficiency gate (decision model, not the
  answer model) judged the context insufficient and it fell back. That's a gate/doc issue, not a model one.
- Costs above are the answer call only. The same run's Micrometer totals show the judge (gpt-4.1, ~62k input tokens)
  and the decision gate (~33k tokens) are bigger than any single answer model, so judging is the real eval cost.

## Koog strategies (F6)

`/kotlin/agent/ask` (Day 9) uses Koog's default single-run strategy: LLM <-> tools until a text answer. F6 builds a
**custom graph strategy** (`KoogStudyStrategy.kt`) that turns a question into a typed study pack - the same shape the
AI Study Guide app needs (answer, key points, sources, quiz):

```
start -> research --toolCalls--> runTools -> collectSources -> readResults --toolCalls--> runTools (loop)
         research / readResults --text--> keepDraft -> toStudyPack (structured) -> unwrap -> finish
```

- `research` = `nodeLLMRequest` (tools offered; the system prompt asks for 1-3 `searchKotlinDocs` calls),
  `runTools` = `nodeExecuteTools` (several calls from one LLM turn run together), `readResults` = `nodeLLMSendToolResults`.
- `collectSources` / `keepDraft` / `unwrap` are custom `node<I, O>`s; per-run state (draft, doc sources the tool
  really returned) lives in the agent `storage`, so one strategy instance serves all requests.
- `toStudyPack` = `nodeLLMRequestStructured<StudyPack>`: Koog builds a JSON schema from the `@Serializable` +
  `@LLMDescription` data classes, sends one example, and a `StructureFixingParser` (2 retries) repairs broken JSON.
  If that still fails, `unwrap` returns the plain draft with `structured=false` instead of a 500.
- The server never trusts the model's `sources`: only files the tool returned survive, and `grounded` is true only if
  the tool found docs **and** the model says they cover the question (labeled fallback otherwise).
- `GET /kotlin/agent/study/graph` prints the graph as a Mermaid diagram (`asMermaidDiagram()`, paste into mermaid.live).

```bash
curl -s -X POST localhost:8080/kotlin/agent/study -H 'Content-Type: application/json' \
  -d '{"question":"Where can the direct subclasses of a sealed class be declared?"}' | jq '{structured, grounded, nodes, toolCalls, pack}'
curl -s -X POST localhost:8080/kotlin/agent/study -H 'Content-Type: application/json' \
  -d '{"question":"When should I use a data class versus a sealed class?"}' | jq '{nodes, tools: [.toolCalls[].args], quiz: .pack.quiz}'
curl -s -X POST localhost:8080/kotlin/agent/study -H 'Content-Type: application/json' \
  -d '{"question":"What is the capital of France?"}' | jq '{grounded, nodes, answer: .pack.answer}'
curl -s localhost:8080/kotlin/agent/study/graph
```

Run on 2026-11-23 (gpt-4o-mini via the Spring AI bridge): sealed question -> 1 search, all 7 nodes, structured OK,
3 quiz items, ~9.8 s; data vs sealed -> 2 searches in one turn, ~11.8 s; capital of France -> no tool call,
`research -> keepDraft -> toStudyPack -> unwrap`, `grounded=false`, ~2.8 s. The graph costs 3 LLM calls on the
grounded path (research, read results, structure) vs 2 for `/agent/ask`. `KoogStudyStrategyTest` runs the whole
graph offline with a scripted `PromptExecutor` (happy path + structured-output fallback).

## Koog multiple tools + memory (F7)

`POST /kotlin/agent/research` is a Koog "research assistant": the default single-run strategy (LLM <-> tools loop)
with three tools, conversation memory and a structured trace. `POST /kotlin/chat/tools` wires the **same** tools into
plain Spring AI tool calling, and `POST /kotlin/agent/compare` runs both side by side.

**Tools** (`ResearchTools.kt`, one class annotated for both frameworks: Koog `@Tool`/`@LLMDescription` via `ToolSet`,
Spring AI `@Tool`/`@ToolParam` via `ToolCallbacks.from`):
- `searchKotlinDocs(query)` - the Qdrant vector search (top 4) with `[source: file.md]` labels.
- `calculate(expression)` - `Calculator.kt`, a ~100-line recursive-descent parser (`+ - * / % ^`, parentheses,
  `sqrt/abs/round`, `pi/e`). No script engine, nothing can be executed; errors go back to the model as `Error: ...`.
- `fetchWebPage(url)` - `WebPageFetcher.kt`, real HTTP but locked down: https only, host on `rag.web.allowed-hosts`
  (default `kotlinlang.org, docs.spring.io, docs.koog.ai, api.koog.ai`, subdomains ok), redirects re-checked (max 3),
  5 s connect / 10 s timeout, 512 KB read cap, HTML -> text (scripts/nav dropped, `<main>` preferred), 4000 chars max.
  So no SSRF to localhost/Qdrant/cloud metadata, no API key needed.

**Memory**: Koog's `ChatMemory` feature (`agents-features-memory`) with our `AgentConversationMemory` as the
`ChatHistoryProvider`; `agent.run(question, conversationId)` loads the conversation before the run and stores it after.
A custom preprocessor keeps *system prompt + last 20 dialogue messages* and drops tool calls/results (ChatMemory
replaces the prompt with the loaded history, so the system prompt must stay in it; and a window must never split a tool
call from its result). In memory for now - F8 swaps in a Postgres `ChatHistoryProvider`.
`GET|DELETE /kotlin/agent/research/{id}/memory` shows / forgets a conversation.

**Tracing / observability**:
- `EventHandler` callbacks feed `AgentTraceRecorder`: the response has `trace` (llm / tool / node events with offset +
  duration, tokens per LLM call), `toolCalls` (args, latency, ok, result preview), `llmCalls`, `inputTokens`, `outputTokens`.
- Koog's `Tracing` feature writes every agent event to the `koog.trace` logger (`KOOG_TRACE_LOG_LEVEL=DEBUG` to see it).
- Micrometer: `agent.runs`, `agent.llm.calls`, `agent.tool.calls{engine,tool,outcome}`, `agent.llm.tokens{engine,type}`
  -> `curl localhost:8080/actuator/metrics/agent.tool.calls?tag=engine:koog`.

```bash
curl -s -X POST localhost:8080/kotlin/agent/research -H 'Content-Type: application/json' \
  -d '{"question":"Where must the direct subclasses of a sealed class be declared?"}' | jq '{answer, toolCalls, latencyMs}'
curl -s -X POST localhost:8080/kotlin/agent/research -H 'Content-Type: application/json' \
  -d '{"question":"What is (1250 * 0.18 + 37) / 4 ?"}' | jq '{answer, toolCalls}'
curl -s -X POST localhost:8080/kotlin/agent/research -H 'Content-Type: application/json' \
  -d '{"question":"Look up what the Koog ChatMemory feature does on https://docs.koog.ai/features/chat-memory/ and what a Kotlin data class generates (local docs). Also compute 24 * 365."}' | jq '{answer, tools: [.toolCalls[] | {tool, latencyMs}], trace}'
# memory: two turns, same conversationId
curl -s -X POST localhost:8080/kotlin/agent/research -H 'Content-Type: application/json' \
  -d '{"question":"Remember this: my project uses Kotlin 2.3 and our build budget is 40 minutes per day.","conversationId":"demo-1"}' | jq .answer
curl -s -X POST localhost:8080/kotlin/agent/research -H 'Content-Type: application/json' \
  -d '{"question":"Which Kotlin version does my project use, and how many hours of build time is my budget per 30-day month?","conversationId":"demo-1"}' | jq '{answer, toolCalls, memoryMessages}'
curl -s localhost:8080/kotlin/agent/research/demo-1/memory | jq
# Koog agent vs Spring AI tool calling, same tools + prompt + model
curl -s -X POST localhost:8080/kotlin/agent/compare -H 'Content-Type: application/json' \
  -d '{"question":"What does the Kotlin compiler generate for a data class (local docs)? Also compute 17.5% of 2400."}' \
  | jq '{koog: (.koog | {latencyMs, llmCalls, inputTokens, outputTokens, tools: [.toolCalls[].tool]}), springAi: (.springAi | {latencyMs, inputTokens, outputTokens, tools: [.toolCalls[].tool]})}'
```

Run on 2026-11-24 (gpt-4o-mini): sealed question -> 1 search, 2 LLM calls, 3.3 s; calculator -> `65.5`, 1.9 s;
multi-tool -> fetchWebPage + searchKotlinDocs + calculate requested in ONE LLM turn, 2 LLM calls, 6.0 s; memory turn 2
answered "Kotlin 2.3" and "20 hours" (calculator 40*30/60) from turn 1, another conversationId did not know it;
compare -> Koog 2.8 s / 1108+159 tokens vs Spring AI 3.1 s / 1109+161 tokens, same 2 tools.

**Koog agent vs Spring AI tool calling** (same tools, prompt, model):

| | Koog `AIAgent` | Spring AI `ChatClient` + tools |
|---|---|---|
| Tool loop | explicit strategy graph (nodes you can replace, F6) | hidden inside `ChatModel` / `ToolCallingManager` |
| Memory | `ChatMemory` feature + `ChatHistoryProvider` | session advisors / `SessionService` (F1) |
| Observability | `EventHandler`, `Tracing`, `OpenTelemetry` features, per-node events | Micrometer observations (`spring.ai.tool`, `gen_ai.client.*`); per-call detail needs a `ToolCallback` wrapper |
| Human approval | custom node | `requiresConfirmation` (F2) |
| Cost / latency (compare run) | 2 LLM calls, 2.8 s, 1108+159 tokens | same round trips, 3.1 s, 1109+161 tokens |

## Postgres + pgvector (F8)

Postgres becomes the app's system of record (prep for the Study Guide SaaS backend) and a second vector store:

| What | Where | Code |
|---|---|---|
| Chat sessions + messages (`conversationId`) | `AI_SESSION`, `AI_SESSION_EVENT`, `AI_SESSION_INDEX` | Spring AI's official `JdbcSessionRepository` (`spring-ai-starter-model-session-jdbc`), plugged into the F1 `SessionService` |
| Users | `app_user` (+ view `chat_session_overview`) | `JdbcUserDirectory`, `UserSessions` |
| Koog research-agent memory (F7) | `agent_memory` (JSONB, one row per conversation) | `JdbcAgentMemoryStore` behind `AgentConversationMemory` |
| Kotlin docs chunks (optional) | `kotlin_docs` (`vector(1536)`, HNSW, cosine) | Spring AI `PgVectorStore` auto-configuration |

Schema is owned by Flyway (`src/main/resources/db/migration`): `V1` is Spring AI 2.1.0's `schema-postgresql.sql` copied
verbatim (Spring AI's own initializer is off: `spring.ai.session.repository.jdbc.initialize-schema=never`), `V2` app_user,
`V3` agent_memory. The pgvector table is created by `PgVectorStore` itself (`initialize-schema: true`).

**Switching the vector store**: `RAG_VECTOR_STORE=qdrant` (default) or `pgvector`. It feeds Spring AI's own switch
`spring.ai.vectorstore.type`, so exactly one store auto-configuration creates the `VectorStore` bean, and RAG,
`/kotlin/docs/*`, the agents' search tool and the evals use it without code changes. `VectorStoreRegistry` builds the
*other* store on demand with the same `spring.ai.vectorstore.*` settings (for ingest + comparison only).

**Users**: optional `X-User-Id` header (1-64 of `[A-Za-z0-9._@-]`, default `anonymous` = `RAG_DEFAULT_USER`). The first
request creates the `app_user` row; a new conversation is created with Spring AI's standard `principalName` session
attribute = the user (indexed in `AI_SESSION_INDEX`). Another user's `conversationId` behaves like an unknown one (404).
Eval runs use the user `rag-evals`. Sessions now live 7 days (`RAG_SESSION_TTL_MINUTES=10080`); expired ones are deleted
hourly (`ExpiredSessionCleaner`). Not real auth yet: in the SaaS backend the header becomes the JWT subject.

### Run on the Mac (Docker Compose)
```bash
./gradlew bootRun                                   # compose.yaml starts qdrant + postgres (pgvector/pgvector:0.8.7-pg17)
RAG_VECTOR_STORE=pgvector ./gradlew bootRun         # same, RAG on pgvector
psql postgresql://rag:rag@localhost:5432/rag -c 'select * from chat_session_overview'
```
Spring Boot's docker-compose support wires the container into `spring.datasource.*` (label
`org.springframework.boot.service-connection: postgres`).

### Run manually (no Docker, e.g. the dev box)
```bash
/workspace/tools/postgres/start-postgres.sh   # Postgres 17 + pgvector 0.8.0, data in /workspace/tools/postgres/data, port 5432, db/user rag/rag
/workspace/tools/qdrant/start-qdrant.sh
./gradlew bootJar
SPRING_DOCKER_COMPOSE_ENABLED=false java -jar build/libs/spring-ai-kotlin-rag-0.0.1-SNAPSHOT.jar
SPRING_DOCKER_COMPOSE_ENABLED=false RAG_VECTOR_STORE=pgvector java -jar build/libs/spring-ai-kotlin-rag-0.0.1-SNAPSHOT.jar
# other database: POSTGRES_URL=jdbc:postgresql://host:5432/db POSTGRES_USER=... POSTGRES_PASSWORD=...
# no Postgres at all (F7 behaviour, in-memory sessions, Qdrant only):
SPRING_PROFILES_ACTIVE=memory SPRING_DOCKER_COMPOSE_ENABLED=false java -jar build/libs/spring-ai-kotlin-rag-0.0.1-SNAPSHOT.jar
```
On your own Postgres: `CREATE EXTENSION vector, hstore, "uuid-ossp"` as a superuser first (PgVectorStore runs
`CREATE EXTENSION IF NOT EXISTS` for them, which a normal user may not be allowed to do).

### Try it
```bash
# which store is active, chunks per store
curl -s localhost:8080/kotlin/vectorstores | jq
# ingest the docs into the inactive store too, then compare (default queries = the 28 golden-set questions)
curl -s -X POST localhost:8080/kotlin/vectorstores/pgvector/ingest | jq
curl -s -X POST localhost:8080/kotlin/vectorstores/compare -H 'Content-Type: application/json' -d '{"topK":4,"repeats":5}' \
  | jq '{activeStore, searchLatency, embeddingLatency, meanOverlap, sameTop1, sameOrder, maxScoreDelta, chunks, differences: [.differences[].query]}'
curl -s -X POST localhost:8080/kotlin/vectorstores/compare -H 'Content-Type: application/json' \
  -d '{"queries":["What does launch return?","Can a sealed class be a data class?"],"topK":3,"repeats":20}' | jq '.perQuery'

# a session owned by a user ...
curl -s -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' -H 'X-User-Id: tomas' \
  -d '{"question":"Remember: my project is called StudyBuddy. How do I restrict which classes can extend a base class?"}' | jq '{conversationId, answerType, answer}'
curl -s localhost:8080/kotlin/chat/sessions -H 'X-User-Id: tomas' | jq
# ... restart the app (even with the other RAG_VECTOR_STORE), then continue it:
curl -s -X POST localhost:8080/kotlin/chat/ask -H 'Content-Type: application/json' -H 'X-User-Id: tomas' \
  -d '{"question":"What is my project called?","conversationId":"<id from above>"}' | jq .answer
curl -s localhost:8080/kotlin/chat/sessions/<id> -H 'X-User-Id: tomas' | jq   # history
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/kotlin/chat/sessions/<id> -H 'X-User-Id: bob'   # 404
# Koog research memory also survives restarts now
curl -s localhost:8080/kotlin/agent/research/demo-1/memory | jq
```

### Results (box, 2026-11-25; Postgres 17.12 + pgvector 0.8.0 HNSW vs Qdrant 1.20.0 binary, 15 chunks, text-embedding-3-small)

`POST /kotlin/vectorstores/compare` (28 golden questions, top-4, 5 repeats = 140 searches per store, query embedding cached):

| | Qdrant (gRPC) | pgvector (JDBC) |
|---|---|---|
| search p50 / p95 (Qdrant active) | 1.77 / 2.34 ms | 1.31 / 1.57 ms |
| search p50 / p95 (pgvector active) | 1.77 / 2.47 ms | 1.49 / 2.21 ms |
| embedding call per query (OpenAI) | p50 193 ms / 179 ms | (same call) |

- Top-4 overlap 100% on all 28 queries, same top-1 on 27, identical order on 25; the 3 differences are swaps of
  near-tied chunks (score gap < 0.02), max score difference for the same chunk 0.0165. Cause: the chunks were embedded
  in two separate ingest runs and OpenAI embeddings are not bit-identical between calls - not the stores.
- Store latency is noise next to the ~0.2 s embedding call and ~2 s LLM call; with 15 chunks both indexes are trivially
  exact. Real differences show up at 100k+ vectors (HNSW params, filtering, memory), not here.
- Evals (`POST /kotlin/evals/run {"variants":["baseline"],"limit":6}`, gpt-4o-mini, judge gpt-4.1): both stores 6/6
  retrieval hit, relevancy, groundedness and correctness; p50 2130 ms (pgvector) vs 2064 ms (Qdrant).
- `/kotlin/chat/ask` "restrict which classes can extend a base class": grounded on sealed-classes.md with both
  (3.1 s Qdrant incl. first-call warm-up, 2.1 s pgvector). A session created on the Qdrant run continued after a restart on
  pgvector ("Your project is called StudyBuddy ..."), and the Koog research memory remembered "40 minutes per day" across
  the restart (calculate 40*30/60 = 20 hours).

**Qdrant vs pgvector, when to pick which**: pgvector = one database for users, sessions, documents and vectors
(transactions, joins, backups, row-level security per tenant - attractive for the SaaS backend); Qdrant = purpose-built,
richer filtering/payload indexes, quantization, horizontal scaling, better at large scale. For the Study Guide app,
starting with pgvector (one Postgres) and keeping the `VectorStore` abstraction to move later is the pragmatic choice.

