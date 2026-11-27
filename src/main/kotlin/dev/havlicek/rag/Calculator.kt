package dev.havlicek.rag

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * F7: a tiny, safe arithmetic evaluator for the calculator tool (recursive descent, no script engine, no eval):
 *
 *   expr   := term (('+' | '-') term)*
 *   term   := unary (('*' | '/' | '%') unary)*
 *   unary  := ('-' | '+') unary | power
 *   power  := atom ('^' unary)?            right-associative, so 2^3^2 = 2^9 and -2^2 = -4
 *   atom   := number | '(' expr ')' | name '(' expr ')' | constant
 */
object Calculator {
    const val MAX_LENGTH = 200
    private val functions: Map<String, (Double) -> Double> = mapOf("sqrt" to ::sqrt, "abs" to ::abs, "round" to ::round)
    private val constants = mapOf("pi" to Math.PI, "e" to Math.E)

    /** @throws IllegalArgumentException for bad syntax, @throws ArithmeticException for division by zero etc. */
    fun evaluate(expression: String): Double {
        require(expression.isNotBlank()) { "Empty expression" }
        require(expression.length <= MAX_LENGTH) { "Expression longer than $MAX_LENGTH characters" }
        val value = Parser(expression).parseAll()
        if (!value.isFinite()) throw ArithmeticException("Result is not a finite number")
        return value
    }

    /** 42.0 -> "42", 0.1 + 0.2 -> "0.3" (12 significant digits hide binary floating point noise). */
    fun format(value: Double): String =
        if (value % 1.0 == 0.0 && abs(value) < 1e15) value.toLong().toString()
        else BigDecimal(value).round(MathContext(12)).stripTrailingZeros().toPlainString()

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseAll(): Double {
            val value = expr()
            skipSpaces()
            if (pos < s.length) fail("Unexpected '${s[pos]}' at position ${pos + 1}")
            return value
        }

        private fun expr(): Double {
            var value = term()
            while (true) value = when {
                eat('+') -> value + term()
                eat('-') -> value - term()
                else -> return value
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) value = when {
                eat('*') -> value * unary()
                eat('/') -> unary().let { if (it == 0.0) throw ArithmeticException("Division by zero") else value / it }
                eat('%') -> unary().let { if (it == 0.0) throw ArithmeticException("Modulo by zero") else value % it }
                else -> return value
            }
        }

        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> power()
        }

        private fun power(): Double {
            val base = atom()
            return if (eat('^')) base.pow(unary()) else base
        }

        private fun atom(): Double {
            skipSpaces()
            if (eat('(')) return expr().also { expect(')') }
            val start = pos
            if (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDoubleOrNull() ?: fail("Bad number '${s.substring(start, pos)}'")
            }
            while (pos < s.length && s[pos].isLetter()) pos++
            val name = s.substring(start, pos).lowercase()
            if (name.isEmpty()) fail(if (pos < s.length) "Unexpected '${s[pos]}' at position ${pos + 1}" else "Unexpected end of expression")
            constants[name]?.let { return it }
            val fn = functions[name] ?: fail("Unknown name '$name' (allowed: ${(functions.keys + constants.keys).joinToString()})")
            expect('(')
            return fn(expr()).also { expect(')') }
        }

        private fun eat(c: Char): Boolean {
            skipSpaces()
            return (pos < s.length && s[pos] == c).also { if (it) pos++ }
        }

        private fun expect(c: Char) {
            if (!eat(c)) fail("Expected '$c' at position ${pos + 1}")
        }

        private fun skipSpaces() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
