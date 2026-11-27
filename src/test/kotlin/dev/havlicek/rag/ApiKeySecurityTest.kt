package dev.havlicek.rag

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** F10: API-key mode of the real filter chain: 401 without/with a wrong key, the key decides the user, no spoofing. */
@WebMvcTest(KotlinController::class)
@Import(SecurityConfig::class)
@TestPropertySource(
    properties = [
        "rag.security.enabled=true",
        "rag.security.api-keys[0].user-id=alice",
        "rag.security.api-keys[0].sha256=${ApiKeySecurityTest.ALICE_HASH}",
        "rag.security.api-keys[0].name=alice-test",
    ]
)
class ApiKeySecurityTest(@Autowired private val mvc: MockMvc) {
    @MockitoBean lateinit var docsService: DocsService
    @MockitoBean lateinit var chatService: ChatService
    @MockitoBean lateinit var koogBridgeService: KoogBridgeService
    @MockitoBean lateinit var koogAgentService: KoogAgentService
    @MockitoBean lateinit var koogStudyService: KoogStudyService
    @MockitoBean lateinit var opsAgentService: OpsAgentService
    @MockitoBean lateinit var ragEvalRunner: RagEvalRunner

    private fun ask(configure: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {}) = mvc.post("/kotlin/chat/ask") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"question":"What is a data class?"}"""
        configure()
    }

    @Test
    fun `no key is 401 problem+json with WWW-Authenticate`() {
        ask().andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            header { string("WWW-Authenticate", containsString("X-API-Key")) }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
            jsonPath("$.status") { value(401) }
            jsonPath("$.type") { value("urn:rag:error:unauthenticated") }
        }
        verifyNoInteractions(chatService)
    }

    @Test
    fun `wrong key is 401, even on unknown paths (no route probing without a key)`() {
        ask { header(API_KEY_HEADER, "rag_wrong") }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Unknown API key") }
        }
        mvc.get("/kotlin/does-not-exist").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `valid key authenticates as the key's user`() {
        given(chatService.ask("What is a data class?", AskOptions(userId = "alice"))).willReturn(RagAnswer("A class [1].", emptyList(), "c-1"))
        ask { header(API_KEY_HEADER, ALICE_KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.answer") { value("A class [1].") }
        }
        // repeating your own id is fine
        ask { header(API_KEY_HEADER, ALICE_KEY); header(USER_HEADER, "alice") }.andExpect { status { isOk() } }
    }

    @Test
    fun `X-User-Id of somebody else is 403`() {
        ask { header(API_KEY_HEADER, ALICE_KEY); header(USER_HEADER, "bob") }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        verifyNoInteractions(chatService)
    }

    @Test
    fun `verifier compares hashes, rejects malformed config`() {
        val verifier = ApiKeyVerifier(listOf(ApiSecurityProperties.ApiKey("alice", ALICE_HASH), ApiSecurityProperties.ApiKey("bob", ApiKeyVerifier.sha256Hex("rag_bob"))))
        assertEquals("alice", verifier.verify(ALICE_KEY)?.userId)
        assertEquals("bob", verifier.verify("rag_bob")?.userId)
        assertNull(verifier.verify(ALICE_KEY.dropLast(1)))
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { ApiKeyVerifier(listOf(ApiSecurityProperties.ApiKey("x", "abc"))) }
    }

    companion object {
        const val ALICE_KEY = "rag_test_alice_0123456789abcdef"
        // sha256("rag_test_alice_0123456789abcdef"), checked in the last test
        const val ALICE_HASH = "d41bac681d1623c85cee0585e75cc0715a694b05d0da0d39ab28178898c701ec"
    }
}
