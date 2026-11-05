# Kotlin Coroutines

Coroutines are lightweight, suspendable computations. A function marked `suspend` can pause without blocking a thread.

## Launching
- `launch` starts a coroutine that returns a `Job` (fire and forget).
- `async` returns a `Deferred<T>`; call `await()` to get the result.
- `runBlocking` bridges blocking code and coroutines; use it in `main` or tests, not in production request handlers.

## Structured concurrency
Every coroutine runs in a `CoroutineScope`. A parent waits for its children, and if a child fails the scope cancels its siblings. `coroutineScope { }` creates a child scope inside a suspend function.

## Dispatchers
`Dispatchers.Default` for CPU work, `Dispatchers.IO` for blocking I/O, `Dispatchers.Main` for UI. Switch with `withContext(Dispatchers.IO) { ... }`.
