package dev.havlicek.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class WebPageFetcherTest {
    private val fetcher = WebPageFetcher(listOf("kotlinlang.org", "docs.koog.ai"))

    @Test
    fun `allows https pages on allow-listed hosts and their subdomains`() {
        assertDoesNotThrow { fetcher.checkUrl("https://kotlinlang.org/docs/sealed-classes.html") }
        assertDoesNotThrow { fetcher.checkUrl("https://www.kotlinlang.org/") }
        assertDoesNotThrow { fetcher.checkUrl("https://docs.koog.ai/features/chat-memory/") }
    }

    @Test
    fun `blocks everything else`() {
        listOf(
            "http://kotlinlang.org/docs/",               // not https
            "https://kotlinlang.org.evil.com/",          // suffix trick
            "https://evilkotlinlang.org/",               // no dot boundary
            "https://kotlinlang.org@evil.com/",          // user-info trick (host is evil.com)
            "https://localhost:6333/collections",        // SSRF to Qdrant
            "https://169.254.169.254/latest/meta-data/", // cloud metadata
            "https://kotlinlang.org:8443/",              // odd port
            "file:///etc/passwd",
            "not a url",
        ).forEach { url -> assertThrows<IllegalArgumentException>(url) { fetcher.checkUrl(url) } }
    }

    @Test
    fun `the tool returns an error text for blocked URLs without any network call`() {
        val result = fetcher.fetchText("https://example.com/")
        assertTrue(result.startsWith("Error: Host example.com is not allowed"), result)
    }

    @Test
    fun `html to text drops scripts and navigation and prefers main content`() {
        val html = """
            <html><head><style>p{}</style><script>alert(1)</script></head>
            <body><nav>Menu Home</nav><main><h1>Sealed classes</h1><p>Subclasses &amp; modules</p></main>
            <footer>(c) JetBrains</footer></body></html>
        """.trimIndent()
        assertEquals("Sealed classes Subclasses & modules", WebPageFetcher.htmlToText(html))
    }
}
