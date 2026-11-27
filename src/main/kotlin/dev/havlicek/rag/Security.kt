package dev.havlicek.rag

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

const val API_KEY_HEADER = "X-API-Key"

/**
 * F10: `rag.security.*`. [enabled]=true (default, prod): every request except health needs an X-API-Key from
 * [apiKeys]; the key decides the user, so X-User-Id can no longer be spoofed. [enabled]=false (profile `dev`, tests):
 * the F8/F9 behaviour - whoever you claim in X-User-Id (default rag.default-user).
 */
@ConfigurationProperties("rag.security")
data class ApiSecurityProperties(
    val enabled: Boolean = true,
    val apiKeys: List<ApiKey> = emptyList(),
) {
    /** Only the SHA-256 (hex) of a key is configured, never the key itself (scripts/new-api-key.sh prints both). */
    data class ApiKey(val userId: String, val sha256: String, val name: String? = null)
}

/** Who is calling: the authenticated principal for the whole request (incl. coroutines and Reactor, see application.yml). */
data class ApiCaller(val userId: String, val via: Via, val keyName: String? = null) {
    enum class Via { API_KEY, DEV_HEADER }
}

class ApiCallerAuthentication(val caller: ApiCaller) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(if (caller.via == ApiCaller.Via.API_KEY) "ROLE_API_CLIENT" else "ROLE_DEV"))) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): ApiCaller = caller
    override fun getName(): String = caller.userId
}

/** The current caller's user id, or null outside a request (startup evals, schedulers). */
fun currentCallerId(): String? = (SecurityContextHolder.getContext().authentication?.principal as? ApiCaller)?.userId

/**
 * F10: SHA-256 the presented key and compare it with EVERY configured hash using MessageDigest.isEqual (constant
 * time, no early exit), so timing doesn't reveal how much of a key matched or which entry it was. Keys are random
 * 256-bit tokens, so a fast hash is enough (bcrypt/argon2 are for low-entropy passwords).
 */
class ApiKeyVerifier(keys: List<ApiSecurityProperties.ApiKey>) {
    private val entries = keys.map { key ->
        val hash = runCatching { HexFormat.of().parseHex(key.sha256.trim().lowercase()) }.getOrNull()
        require(hash != null && hash.size == 32) { "rag.security.api-keys: sha256 for user '${key.userId}' must be 64 hex chars" }
        UserDirectory.requireValidId(key.userId)
        hash to key
    }

    val size: Int get() = entries.size

    fun verify(presented: String): ApiSecurityProperties.ApiKey? {
        val digest = sha256(presented)
        var match: ApiSecurityProperties.ApiKey? = null
        for ((hash, key) in entries) if (MessageDigest.isEqual(hash, digest)) match = key
        return match
    }

    companion object {
        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        fun sha256Hex(value: String): String = HexFormat.of().formatHex(sha256(value))
    }
}

/** Writes RFC 9457 bodies from filters (they run before Spring MVC, so the @RestControllerAdvice can't help there). */
object ProblemResponses {
    private val json = JsonMapper.builder().build()

    fun write(response: HttpServletResponse, problem: ProblemDetail, headers: Map<String, String> = emptyMap()) {
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        headers.forEach(response::setHeader)
        val body = linkedMapOf<String, Any?>(
            "type" to problem.type.toString(), "title" to problem.title, "status" to problem.status,
            "detail" to problem.detail, "instance" to problem.instance?.toString(),
        ).filterValues { it != null } + problem.properties.orEmpty()
        json.writeValue(response.outputStream, body)
    }
}

/**
 * F10: stateless authentication. API-key mode: valid key -> [ApiCaller] for the key's user; unknown key -> 401 at
 * once; X-User-Id that differs from the key's user -> 403 (a key can't act for someone else). No key -> anonymous,
 * so public endpoints work and protected ones get the entry point's 401. Dev mode: X-User-Id (or the default user).
 */
class ApiKeyAuthenticationFilter(
    private val props: ApiSecurityProperties,
    private val defaultUser: String,
) : OncePerRequestFilter() {
    private val verifier = ApiKeyVerifier(props.apiKeys)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val caller = if (props.enabled) {
            val key = request.getHeader(API_KEY_HEADER)?.trim()
            if (key.isNullOrEmpty()) null else {
                val match = verifier.verify(key)
                    ?: return ProblemResponses.write(response, ErrorCode.UNAUTHENTICATED.problem("Unknown API key"), WWW_AUTHENTICATE)
                val claimed = request.getHeader(USER_HEADER)
                if (claimed != null && claimed != match.userId) {
                    return ProblemResponses.write(response, ErrorCode.FORBIDDEN.problem("$USER_HEADER does not match the user of this API key"))
                }
                ApiCaller(match.userId, ApiCaller.Via.API_KEY, match.name)
            }
        } else {
            ApiCaller(request.getHeader(USER_HEADER) ?: defaultUser, ApiCaller.Via.DEV_HEADER)
        }
        if (caller != null) {
            SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext().apply { authentication = ApiCallerAuthentication(caller) })
        }
        chain.doFilter(request, response)
    }

    companion object {
        val WWW_AUTHENTICATE = mapOf(HttpHeaders.WWW_AUTHENTICATE to "ApiKey header=\"$API_KEY_HEADER\"")
    }
}

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiSecurityProperties::class, RagProperties::class)
class SecurityConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun apiSecurityFilterChain(http: HttpSecurity, props: ApiSecurityProperties, rag: RagProperties): SecurityFilterChain {
        if (!props.enabled) log.warn("rag.security.enabled=false: X-User-Id is trusted as-is (dev mode, never in production)")
        else if (props.apiKeys.isEmpty()) log.warn("rag.security.enabled=true but rag.security.api-keys is empty: every API call will be 401")
        else log.info("API key auth on, {} key(s) configured", props.apiKeys.size)
        http {
            csrf { disable() }            // no cookies/browser sessions: CSRF needs ambient credentials, API keys are explicit
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            requestCache { requestCache = org.springframework.security.web.savedrequest.NullRequestCache() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                // async (suspend controllers, SSE) and error dispatches belong to a request that was already authorized
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ASYNC), permitAll)
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            addFilterBefore<AnonymousAuthenticationFilter>(ApiKeyAuthenticationFilter(props, rag.defaultUser))
            exceptionHandling {
                authenticationEntryPoint = { _, response, _ ->
                    ProblemResponses.write(response, ErrorCode.UNAUTHENTICATED.problem("Send your API key in the $API_KEY_HEADER header"),
                        ApiKeyAuthenticationFilter.WWW_AUTHENTICATE)
                }
                accessDeniedHandler = { _, response, _ ->
                    ProblemResponses.write(response, ErrorCode.FORBIDDEN.problem("You are not allowed to do this"))
                }
            }
        }
        return http.build()
    }

    /** API keys are the only credentials; also stops Boot from creating a default user with a generated password. */
    @Bean
    fun authenticationManager() = AuthenticationManager { throw BadCredentialsException("Use the $API_KEY_HEADER header") }
}

/** F10: controller parameter = the API key's user id; in dev mode / without security the raw X-User-Id header (or null). */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CallerId

class CallerIdArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) = parameter.hasParameterAnnotation(CallerId::class.java)

    override fun resolveArgument(
        parameter: MethodParameter, mavContainer: ModelAndViewContainer?, webRequest: NativeWebRequest, binderFactory: WebDataBinderFactory?,
    ): String? {
        val caller = SecurityContextHolder.getContext().authentication?.principal as? ApiCaller
        // dev mode: exactly what F8/F9 controllers saw (header or null -> their own rag.default-user fallback)
        return if (caller?.via == ApiCaller.Via.API_KEY) caller.userId else webRequest.getHeader(USER_HEADER)
    }
}

/** A WebMvcConfigurer component, so @WebMvcTest slices get the resolver too. */
@Component
class CallerWebConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += CallerIdArgumentResolver()
    }
}
