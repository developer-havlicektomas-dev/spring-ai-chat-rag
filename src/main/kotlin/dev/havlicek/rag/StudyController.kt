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
 * Errors: 400 invalid input, 402 not in your plan, 429 daily quota used up - F10: RFC 9457 problem+json from ApiExceptionHandler.
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
        @CallerId user: String?,
    ): StudyGuideResponse = study.generate(user(user), request.copy(text = request.text.trim()))

    /** Plan, limits and today's usage, so the app can show "2 of 3 free generations left". */
    @GetMapping("/usage")
    fun usage(@CallerId user: String?): StudyUsage = guard.usage(user(user))

    /** DEV ONLY (rag.study.dev-plan-switch=true): switch your own plan. In the real app billing webhooks do this. */
    @PutMapping("/plan")
    fun setPlan(@Valid @RequestBody request: PlanRequest, @CallerId user: String?): ResponseEntity<Any> {
        if (!props.devPlanSwitch) throw NoSuchElementException("Not found")
        val plan = Plan.of(request.plan)
        users.setPlan(user(user), plan.wire)
        return ResponseEntity.ok(guard.usage(user(user)))
    }

    private fun user(header: String?) = UserDirectory.requireValidId(header ?: rag.defaultUser)
}
