# Null Safety

Kotlin types are non-nullable by default. `String` cannot hold null; `String?` can.

## Operators
- Safe call `a?.length` returns null if `a` is null.
- Elvis `a?.length ?: 0` supplies a default.
- Not-null assertion `a!!` throws `NullPointerException` when null; avoid it where possible.
- `let`: `a?.let { println(it) }` runs only when not null.

## Smart casts
After `if (a != null)`, the compiler treats `a` as non-null inside the branch (for local `val`s and stable properties).

## Platform types
Values from Java are platform types (`String!`); Kotlin can't know their nullability, so annotate Java code with `@Nullable`/`@NonNull`.
