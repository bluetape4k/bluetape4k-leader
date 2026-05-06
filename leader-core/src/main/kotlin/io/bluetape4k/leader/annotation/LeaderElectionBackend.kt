package io.bluetape4k.leader.annotation

/**
 * 클래스 또는 메서드 단위로 사용할 [io.bluetape4k.leader.LeaderElectorFactory] /
 * [io.bluetape4k.leader.LeaderGroupElectorFactory] 빈을 지정하는 어노테이션.
 *
 * ## 탐색 우선순위 (7단계 fallback)
 *
 * 1. `@LeaderElection(bean = "...")` 또는 `@LeaderGroupElection(bean = "...")` 메서드 필드 명시
 * 2. 메서드 `@LeaderElectionBackend`
 * 3. 선언 클래스 `@LeaderElectionBackend`
 * 4. 패키지 `@LeaderElectionBackend` (`package.kt` 또는 `package-info.java`)
 * 5. 단일 등록 factory 빈
 * 6. `@Primary` factory 빈
 * 7. ambiguous → [org.springframework.beans.factory.NoUniqueBeanDefinitionException]
 *
 * ## 사용 예
 * ```kotlin
 * // 클래스 단위 — 클래스 내 모든 @LeaderElection 메서드에 적용
 * @LeaderElectionBackend("redissonLeaderElectionFactory")
 * class PaymentService {
 *     @LeaderElection(name = "payment-lock")
 *     fun process() { ... }
 * }
 *
 * // 메서드 단위 — 클래스 레벨 설정 오버라이드
 * class AnalyticsService {
 *     @LeaderElectionBackend("mongoLeaderElectionFactory")
 *     @LeaderElection(name = "analytics-lock")
 *     fun analyze() { ... }
 * }
 *
 * // 패키지 단위 (package.kt)
 * @file:LeaderElectionBackend("mongoLeaderElectionFactory")
 * package com.example.analytics
 * ```
 *
 * @property bean 사용할 factory 빈 이름 (literal only)
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElectionBackend(val bean: String)
