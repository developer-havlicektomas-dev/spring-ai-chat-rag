package dev.havlicek.rag

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Configuration
@EnableConfigurationProperties(StudyProperties::class)
class StudyConfig {
    @Bean
    fun studyGuard(users: UserDirectory, usage: StudyUsageStore, props: StudyProperties) = StudyGuard(users, usage, props)
}

data class PlanRequest(@field:NotBlank val plan: String)

/**
 * F9: AI Study Guide backend spike. X-User-Id = the app user (F8); its app_user.plan decides what is allowed.
 * Errors: 400 invalid input, 402 not in your plan, 429 daily quota used up - all with a JSON body the app can show.
 */
@RestController
@RequestMapping("/kotlin/study")
class StudyController(
    private val study: StudyGuideService,
    private val guard: StudyGuard,
    private val users: UserDirectory,
    private val props: StudyProperties,
    private val rag: RagProperties,
) {
    @PostMapping("/generate")
    suspend fun generate(
        @Valid @RequestBody request: StudyGenerateRequest,
        @RequestHeader(USER_HEADER, required = false) user: String?,
    ): StudyGuideResponse = study.generate(user(user), request.copy(text = request.text.trim()))

    /** Plan, limits and today's usage, so the app can show "2 of 3 free generations left". */
    @GetMapping("/usage")
    fun usage(@RequestHeader(USER_HEADER, required = false) user: String?): StudyUsage = guard.usage(user(user))

    /** DEV ONLY (rag.study.dev-plan-switch=true): switch your own plan. In the real app billing webhooks do this. */
    @PutMapping("/plan")
    fun setPlan(@Valid @RequestBody request: PlanRequest, @RequestHeader(USER_HEADER, required = false) user: String?): ResponseEntity<Any> {
        if (!props.devPlanSwitch) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Not found"))
        val plan = Plan.of(request.plan)
        users.setPlan(user(user), plan.wire)
        return ResponseEntity.ok(guard.usage(user(user)))
    }

    private fun user(header: String?) = UserDirectory.requireValidId(header ?: rag.defaultUser)

    @ExceptionHandler(PlanLimitException::class)
    fun paymentRequired(e: PlanLimitException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(
            mapOf(
                "error" to e.message, "code" to "PLAN_LIMIT", "reason" to e.reason, "plan" to e.plan,
                "limit" to e.limit, "requested" to e.requested, "upgradeTo" to Plan.PRO,
            )
        )

    @ExceptionHandler(QuotaExceededException::class)
    fun quotaExceeded(e: QuotaExceededException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", maxOf(1, java.time.Duration.between(java.time.Instant.now(), e.resetsAt).seconds).toString())
            .body(
                mapOf(
                    "error" to e.message, "code" to "DAILY_QUOTA_EXCEEDED", "plan" to e.plan, "used" to e.used, "limit" to e.limit,
                    "resetsAt" to e.resetsAt, "upgradeTo" to if (e.plan == Plan.FREE) Plan.PRO else null,
                )
            )

    @ExceptionHandler(InvalidUserIdException::class, IllegalArgumentException::class)
    fun badRequest(e: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
