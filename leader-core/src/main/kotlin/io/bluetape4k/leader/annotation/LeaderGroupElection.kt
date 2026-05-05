package io.bluetape4k.leader.annotation

/**
 * 메서드를 분산 환경에서 다중 리더 (semaphore-based) 진입으로 보호하는 어노테이션.
 *
 * ## 동작
 * - 메서드 호출 시 [name] 으로 [maxLeaders] 개 슬롯 중 하나의 획득 시도. 성공 시 본문 실행, 실패 시 [failureMode] 에 따라 분기.
 * - 본 PR은 sync `T?` 반환만 지원. suspend / `Mono<T>` / `Flow<T>` 는 후속 [#80].
 * - [maxLeaders] ≤ 1 시 startup fail (단일 리더는 [LeaderElection] 사용).
 *
 * ## 사용 예
 * ```kotlin
 * @LeaderGroupElection(name = "batch-shard", maxLeaders = 3, leaseTime = "PT5M")
 * fun batch() { ... }
 *
 * @LeaderGroupElection(name = "'process-' + #region", maxLeaders = 2)
 * fun processRegion(region: String) { ... }
 * ```
 *
 * @property name 락 이름 (필수). plain SpEL + `${...}` Spring property placeholder
 * @property maxLeaders 동시 리더 수 (≥2 필수). ≤1 시 startup fail
 * @property waitTime 슬롯 획득 대기 시간 — 빈 문자열 시 property 또는 코어 default 폴백
 * @property leaseTime 슬롯 보유 시간 — 빈 문자열 시 property 또는 코어 default 폴백
 * @property bean 사용할 [io.bluetape4k.leader.LeaderGroupElectorFactory] 빈 이름 (literal only)
 * @property failureMode 백엔드 예외 처리 정책. default `RETHROW`
 *
 * @see LeaderElection 단일 리더 변형
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderGroupElection(
    val name: String,
    val maxLeaders: Int = -1,
    val waitTime: String = "",
    val leaseTime: String = "",
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
)
