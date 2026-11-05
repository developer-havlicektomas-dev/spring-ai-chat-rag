# Data Classes

`data class User(val name: String, val age: Int)` makes the compiler generate `equals()`, `hashCode()`, `toString()`, `copy()` and `componentN()` functions from properties in the primary constructor.

## copy
`val older = user.copy(age = user.age + 1)` creates a new instance changing only some fields, which suits immutable models.

## Destructuring
`val (name, age) = user` uses the generated `component1()` and `component2()`.

## Rules
The primary constructor needs at least one parameter, and all must be `val` or `var`. Data classes cannot be `abstract`, `open`, `sealed` or `inner`. Properties declared in the class body are excluded from generated methods.
