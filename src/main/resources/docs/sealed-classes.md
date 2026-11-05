# Sealed Classes and when

A `sealed class` or `sealed interface` restricts its subclasses to the same module and package, so the compiler knows all cases.

```kotlin
sealed interface Result<out T>
data class Success<T>(val value: T) : Result<T>
data class Failure(val error: Throwable) : Result<Nothing>
```

A `when` over a sealed type is exhaustive, so no `else` branch is needed, and adding a new subtype causes a compile error wherever a case is missing. This makes sealed hierarchies ideal for modelling states and results.
