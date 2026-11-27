package dev.havlicek.rag

import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.PermissionDeniedException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ContentFilteredException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.io.InterruptedIOException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

/**
 * F10: every error the API returns, as a stable machine-readable `code` (ProblemDetail extension member) plus the
 * HTTP status. Clients switch on `code`, never on `detail` (human text, may change).
 */
enum class ErrorCode(val status: HttpStatus, val title: String) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Missing or invalid API key"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    PLAN_LIMIT(HttpStatus.PAYMENT_REQUIRED, "Not included in your plan"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Request body too large"),
    CONTENT_FILTERED(HttpStatus.UNPROCESSABLE_CONTENT, "Content was filtered"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    DAILY_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Daily quota used up"),
    BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Daily LLM budget used up"),
    LLM_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "LLM features are temporarily disabled"),
    SPEND_CAP_REACHED(HttpStatus.SERVICE_UNAVAILABLE, "LLM features are paused for today"),
    LLM_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE, "The AI provider is rate limiting us"),
    LLM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The AI provider is unavailable"),
    LLM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The AI provider did not answer in time"),
    LLM_BAD_RESPONSE(HttpStatus.BAD_GATEWAY, "The AI provider rejected or garbled the request"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    /** RFC 9457 "type": a stable URI per code (documented in the README error table). */
    val type: URI = URI.create("urn:rag:error:" + name.lowercase().replace('_', '-'))

    fun problem(detail: String?, properties: Map<String, Any?> = emptyMap()): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: title).also { p ->
            p.type = type
            p.title = title
            p.setProperty("code", name)
            properties.forEach { (k, v) -> if (v != null) p.setProperty(k, v) }
        }
}

/** F10: base for errors that already know their code, extra ProblemDetail members and headers. */
open class ApiException(
    val code: ErrorCode,
    message: String,
    val properties: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

/** Upstream LLM failure, classified into a safe code + message (the provider's own message is only logged). */
data class UpstreamFailure(val code: ErrorCode, val detail: String, val retryAfterSeconds: Long? = null)

object UpstreamErrors {
    private val log = LoggerFactory.getLogger(UpstreamErrors::class.java)

    /** Walks the cause chain (Koog, coroutines and Reactor wrap the SDK exception) and maps provider errors. */
    fun classify(error: Throwable): UpstreamFailure? {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(12).toList()
        if (chain.any { it is TimeoutException || it is InterruptedIOException || it.javaClass.name == "kotlinx.coroutines.TimeoutCancellationException" }) {
            return UpstreamFailure(ErrorCode.LLM_TIMEOUT, "The AI provider did not answer in time. Please retry in a moment.")
        }
        for (e in chain) {
            when (e) {
                is RateLimitException -> return UpstreamFailure(ErrorCode.LLM_RATE_LIMITED, "The AI provider is busy. Please retry shortly.", 30)
                is UnauthorizedException, is PermissionDeniedException -> {
                    log.error("AI provider rejected our credentials ({}), check OPENAI_API_KEY", e.javaClass.simpleName)
                    return UpstreamFailure(ErrorCode.LLM_UNAVAILABLE, "The AI provider is unavailable. Please retry later.", 60)
                }
                is OpenAIServiceException ->
                    return if (e.statusCode() >= 500) UpstreamFailure(ErrorCode.LLM_UNAVAILABLE, "The AI provider is unavailable. Please retry later.", 15)
                    else UpstreamFailure(ErrorCode.LLM_BAD_RESPONSE, "The AI provider rejected the request (HTTP ${e.statusCode()}).")
                is OpenAIIoException -> return UpstreamFailure(ErrorCode.LLM_UNAVAILABLE, "Could not reach the AI provider. Please retry later.", 15)
                is OpenAIInvalidDataException -> return UpstreamFailure(ErrorCode.LLM_BAD_RESPONSE, "The AI provider returned an unreadable response.")
            }
        }
        return null
    }

    /** For places that report errors inside a 200 body (SSE `error` event, the compare endpoint): never the raw message. */
    fun safeMessage(error: Throwable): String = (error as? ApiException)?.message
        ?: classify(error)?.let { "${it.code}: ${it.detail}" }
        ?: "${ErrorCode.INTERNAL_ERROR}: ${ErrorCode.INTERNAL_ERROR.title}"
}

/**
 * F10: the one place that turns exceptions into RFC 9457 `application/problem+json` responses (replaces the per
 * controller handlers of F1-F9). Spring MVC's own exceptions (bad JSON, 404 route, 405, ...) come from
 * [ResponseEntityExceptionHandler] and get a `code` too. 5xx never contain exception messages or stack traces.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ProblemDetail> = respond(e.code.problem(e.message, e.properties), e.headers)

    /** Our own validated-input errors: the messages are written by us and safe to show. */
    @ExceptionHandler(
        InvalidFilterException::class, InvalidChunkSettingsException::class, InvalidEvalVariantException::class,
        InvalidModelException::class, InvalidUserIdException::class, IllegalArgumentException::class,
    )
    fun badRequest(e: RuntimeException) = respond(ErrorCode.INVALID_REQUEST.problem(e.message))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException) = respond(ErrorCode.NOT_FOUND.problem(e.message))

    @ExceptionHandler(ConversationStateException::class)
    fun conflict(e: ConversationStateException) = respond(ErrorCode.CONFLICT.problem(e.message))

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(e: AccessDeniedException) = respond(ErrorCode.FORBIDDEN.problem("You are not allowed to do this"))

    /** F9 402, same members as before (code, reason, plan, limit, requested, upgradeTo); "error" became RFC 9457 "detail". */
    @ExceptionHandler(PlanLimitException::class)
    fun planLimit(e: PlanLimitException) = respond(
        ErrorCode.PLAN_LIMIT.problem(
            e.message,
            mapOf("reason" to e.reason, "plan" to e.plan, "limit" to e.limit, "requested" to e.requested, "upgradeTo" to Plan.PRO),
        )
    )

    /** F9 429 (Study Guide generations per day) - unchanged members, plus Retry-After. */
    @ExceptionHandler(QuotaExceededException::class)
    fun studyQuota(e: QuotaExceededException) = respond(
        ErrorCode.DAILY_QUOTA_EXCEEDED.problem(
            e.message,
            mapOf(
                "plan" to e.plan, "used" to e.used, "limit" to e.limit, "resetsAt" to e.resetsAt,
                "upgradeTo" to if (e.plan == Plan.FREE) Plan.PRO else null,
            ),
        ),
        mapOf(HttpHeaders.RETRY_AFTER to retryAfter(e.resetsAt).toString()),
    )

    @ExceptionHandler(ContentFilteredException::class)
    fun contentFiltered(e: ContentFilteredException) = respond(ErrorCode.CONTENT_FILTERED.problem("The AI provider's content filter blocked this request or answer."))

    /** Everything else: an upstream LLM failure (503/504/502) or a bug (500). Logged here, never echoed. */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ProblemDetail> {
        val upstream = UpstreamErrors.classify(e)
        return if (upstream != null) {
            log.warn("Upstream LLM call failed: {} ({})", upstream.code, e.javaClass.name)
            respond(upstream.code.problem(upstream.detail), upstream.retryAfterSeconds?.let { mapOf(HttpHeaders.RETRY_AFTER to it.toString()) }.orEmpty())
        } else {
            log.error("Unhandled exception", e)
            respond(ErrorCode.INTERNAL_ERROR.problem("Something went wrong on our side."))
        }
    }

    /** @Valid body: list every field error, so a form can show them next to the inputs. */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException, headers: HttpHeaders, status: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> = ResponseEntity.status(status).headers(headers).body(
        ErrorCode.VALIDATION_FAILED.problem(
            "${ex.bindingResult.errorCount} invalid field(s)",
            mapOf("errors" to ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) }),
        )
    )

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException, headers: HttpHeaders, status: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any> = ResponseEntity.status(status).headers(headers).body(
        ErrorCode.VALIDATION_FAILED.problem(
            "Invalid parameters",
            mapOf("errors" to ex.parameterValidationResults.flatMap { r ->
                r.resolvableErrors.map { mapOf("field" to r.methodParameter.parameterName, "message" to it.defaultMessage) }
            }),
        )
    )

    /** Framework errors (malformed JSON, unknown route, wrong method, ...) keep Spring's status, gain our `code`. */
    override fun handleExceptionInternal(
        ex: Exception, body: Any?, headers: HttpHeaders, statusCode: HttpStatusCode, request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = (body as? ProblemDetail) ?: ProblemDetail.forStatus(statusCode)
        if (problem.properties?.containsKey("code") != true) {
            problem.setProperty("code", codeFor(statusCode).name)
        }
        if (statusCode.is5xxServerError) problem.detail = codeFor(statusCode).title
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun codeFor(status: HttpStatusCode) = when (status.value()) {
        400 -> ErrorCode.INVALID_REQUEST
        401 -> ErrorCode.UNAUTHENTICATED
        403 -> ErrorCode.FORBIDDEN
        404 -> ErrorCode.NOT_FOUND
        409 -> ErrorCode.CONFLICT
        413 -> ErrorCode.PAYLOAD_TOO_LARGE
        429 -> ErrorCode.RATE_LIMITED
        503 -> ErrorCode.LLM_UNAVAILABLE
        else -> if (status.is4xxClientError) ErrorCode.INVALID_REQUEST else ErrorCode.INTERNAL_ERROR
    }

    private fun respond(problem: ProblemDetail, headers: Map<String, String> = emptyMap()): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(problem.status).headers { h -> headers.forEach(h::set) }.body(problem)

    companion object {
        fun retryAfter(resetsAt: Instant): Long = maxOf(1, Duration.between(Instant.now(), resetsAt).seconds)
    }
}
