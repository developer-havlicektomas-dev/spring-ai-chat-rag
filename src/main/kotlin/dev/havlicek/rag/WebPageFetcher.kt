package dev.havlicek.rag

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * F7: the "web" tool, kept deliberately boring and safe (no API key, no search engine):
 * - https only, host must be on an allow-list (exact host or a subdomain of it) -> no SSRF to localhost/Qdrant/metadata IPs
 * - redirects are followed by hand (max 3) and every hop is checked against the allow-list again
 * - 5 s connect / 10 s request timeout, at most [maxBytes] read, at most [maxChars] of text returned to the model
 * - HTML -> plain text (scripts/styles/nav dropped, prefers <main>/<article>)
 */
class WebPageFetcher(
    allowedHosts: Collection<String>,
    private val maxBytes: Int = 512 * 1024,
    private val maxChars: Int = 4000,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val allowedHosts: Set<String> = allowedHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** @throws IllegalArgumentException if the URL is not https or not on the allow-list. */
    fun checkUrl(url: String): URI {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid URL: $url")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only https URLs are allowed" }
        require(uri.userInfo == null) { "URLs with user info are not allowed" }
        require(uri.port == -1 || uri.port == 443) { "Only the default https port is allowed" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("URL has no host")
        require(allowedHosts.any { host == it || host.endsWith(".$it") }) {
            "Host $host is not allowed (allowed: ${allowedHosts.joinToString()})"
        }
        return uri
    }

    /** Tool entry point: never throws, the model gets "Error: ..." instead. */
    fun fetchText(url: String): String = try {
        fetch(checkUrl(url))
    } catch (e: IllegalArgumentException) {
        "Error: ${e.message}"
    } catch (e: Exception) {
        log.warn("fetchWebPage {} failed: {}", url, e.toString())
        "Error: could not fetch $url (${e.javaClass.simpleName})"
    }

    private fun fetch(start: URI): String {
        var uri = start
        repeat(MAX_REDIRECTS + 1) {
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "spring-ai-kotlin-rag-learning/0.1 (research tool)")
                .header("Accept", "text/html,text/plain")
                .GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() in 300..399) {
                response.body().close()
                val location = response.headers().firstValue("location").orElse(null) ?: return "Error: redirect without location"
                uri = checkUrl(uri.resolve(location).toString()) // every hop must be allowed too
                return@repeat
            }
            val bytes = response.body().use { it.readNBytes(maxBytes) }
            if (response.statusCode() != 200) return "Error: HTTP ${response.statusCode()} for $uri"
            val type = response.headers().firstValue("content-type").orElse("")
            if (!type.startsWith("text/")) return "Error: unsupported content type '$type'"
            val text = htmlToText(String(bytes, Charsets.UTF_8))
            log.info("fetchWebPage {} -> {} chars", uri, text.length)
            return "[source: $uri]\n" + if (text.length > maxChars) text.take(maxChars) + " ...[truncated]" else text
        }
        return "Error: too many redirects"
    }

    companion object {
        const val MAX_REDIRECTS = 3
        private val dropBlocks = Regex("(?is)<(script|style|noscript|svg|nav|header|footer|aside)\\b.*?</\\1>")
        private val mainBlock = Regex("(?is)<(main|article)\\b[^>]*>(.*)</\\1>")
        private val tags = Regex("(?s)<[^>]+>")
        private val entities = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&#x27;" to "'")

        /** Good enough for docs pages; a real crawler would use jsoup. */
        fun htmlToText(html: String): String {
            val withoutNoise = dropBlocks.replace(html, " ")
            val body = mainBlock.find(withoutNoise)?.groupValues?.get(2) ?: withoutNoise
            var text = tags.replace(body, " ")
            entities.forEach { (k, v) -> text = text.replace(k, v) }
            return text.replace(Regex("\\s+"), " ").trim()
        }
    }
}
