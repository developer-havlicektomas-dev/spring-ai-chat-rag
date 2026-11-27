package dev.havlicek.rag

import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * F10: web slices with the real filter chain in dev mode (rag.security.enabled=false): X-User-Id behaves like in
 * F8/F9, so the existing controller tests keep testing ownership via the header.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(SecurityConfig::class)
@TestPropertySource(properties = ["rag.security.enabled=false"])
annotation class DevSecurity
