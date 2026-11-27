package dev.havlicek.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CalculatorTest {
    private fun calc(expression: String) = Calculator.format(Calculator.evaluate(expression))

    @Test
    fun `respects precedence, parentheses and right-associative powers`() {
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("512", calc("2 ^ 3 ^ 2"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("1", calc("7 % 3"))
        assertEquals("60", calc("(1200 * 0.15) / 3"))
        assertEquals("0.3", calc("0.1 + 0.2")) // no floating point noise
    }

    @Test
    fun `supports a few functions and constants`() {
        assertEquals("12", calc("sqrt(144)"))
        assertEquals("5", calc("abs(-5)"))
        assertEquals("3", calc("round(2.6)"))
        assertEquals("3.14159265359", calc("pi"))
    }

    @Test
    fun `rejects anything that is not arithmetic`() {
        assertThrows<IllegalArgumentException> { Calculator.evaluate("java.lang.Runtime.getRuntime()") }
        assertThrows<IllegalArgumentException> { Calculator.evaluate("2 +") }
        assertThrows<IllegalArgumentException> { Calculator.evaluate("(1 + 2") }
        assertThrows<IllegalArgumentException> { Calculator.evaluate("1".repeat(Calculator.MAX_LENGTH + 1)) }
        assertThrows<ArithmeticException> { Calculator.evaluate("1 / (2 - 2)") }
    }

    @Test
    fun `the tool reports errors as text instead of throwing`() {
        val tools = ResearchTools(org.mockito.Mockito.mock(DocsService::class.java), WebPageFetcher(emptyList()))
        assertEquals("6 * 7 = 42", tools.calculate("6 * 7"))
        assertTrue(tools.calculate("6 / 0").startsWith("Error: Division by zero"))
        assertTrue(tools.calculate("rm -rf /").startsWith("Error:"))
    }
}
