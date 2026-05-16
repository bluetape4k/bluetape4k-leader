package io.bluetape4k.leader.annotation

/**
 * Annotation that specifies the [io.bluetape4k.leader.LeaderElectorFactory] /
 * [io.bluetape4k.leader.LeaderGroupElectorFactory] bean to use at class or method level.
 *
 * ## Lookup Priority (7-step fallback)
 *
 * 1. `@LeaderElection(bean = "...")` or `@LeaderGroupElection(bean = "...")` method field explicit
 * 2. Method-level `@LeaderElectionBackend`
 * 3. Declaring class `@LeaderElectionBackend`
 * 4. Package `@LeaderElectionBackend` (`package.kt` or `package-info.java`)
 * 5. Single registered factory bean
 * 6. `@Primary` factory bean
 * 7. Ambiguous → [org.springframework.beans.factory.NoUniqueBeanDefinitionException]
 *
 * ## Usage Example
 * ```kotlin
 * // Class level — applies to all @LeaderElection methods in the class
 * @LeaderElectionBackend("redissonLeaderElectionFactory")
 * class PaymentService {
 *     @LeaderElection(name = "payment-lock")
 *     fun process() { ... }
 * }
 *
 * // Method level — overrides class-level setting
 * class AnalyticsService {
 *     @LeaderElectionBackend("mongoLeaderElectionFactory")
 *     @LeaderElection(name = "analytics-lock")
 *     fun analyze() { ... }
 * }
 *
 * // Package level (package.kt)
 * @file:LeaderElectionBackend("mongoLeaderElectionFactory")
 * package com.example.analytics
 * ```
 *
 * @property bean the factory bean name to use (literal only)
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElectionBackend(val bean: String)
