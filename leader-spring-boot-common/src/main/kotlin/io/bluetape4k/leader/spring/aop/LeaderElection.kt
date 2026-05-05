package io.bluetape4k.leader.spring.aop

/**
 * 메서드를 분산 환경에서 단일 리더 선출 진입으로 보호하는 어노테이션.
 *
 * ## 동작
 * - 메서드 호출 시 [name] 으로 락 획득 시도. 성공 시 본문 실행, 실패 시 [failureMode] 에 따라 분기.
 * - 본 PR은 sync `T?` 반환만 지원. suspend / `Mono<T>` / `Flow<T>` 는 후속 [#80].
 *
 * ## SpEL 평가
 * [name] 은 plain SpEL — 리터럴 prefix 는 따옴표 필수:
 * - ✅ `name = "daily-job"` (정적)
 * - ✅ `name = "'process-' + #region"`
 * - ✅ `name = "#user.tenantId"`
 * - ✅ `name = "\${spring.application.name}-warmup"` (Spring property placeholder 후 plain SpEL)
 * - ❌ `name = "process-#region"` — `process-` 가 식별자로 해석되어 startup pre-parse 실패
 *
 * ## 보안
 * - default 로 SpEL 메서드 호출 차단 (`#user.delete()` 같은 부작용 표현식).
 *   property `bluetape4k.leader.aop.spel.allow-method-invocation=true` 로 명시적 opt-in 가능.
 *
 * ## 사용 예
 * ```kotlin
 * @Scheduled(cron = "0 0 2 * * *")
 * @LeaderElection(name = "daily-settlement", leaseTime = "PT1H")
 * fun dailySettlement() { ... }
 *
 * @LeaderElection(name = "'process-' + #region", failureMode = LeaderAspectFailureMode.SKIP)
 * fun process(region: String): Result? = service.process(region)
 *
 * // 다중 백엔드 환경에서 명시적 factory 선택
 * @LeaderElection(name = "audit", bean = "redissonLeaderElectionFactory")
 * fun audit() { ... }
 * ```
 *
 * ## 미선출 시 매핑
 * - `T?` 반환 메서드: `null`
 * - `Unit` 메서드: 본문 미실행
 *
 * @property name 락 이름 (필수). plain SpEL + `${...}` Spring property placeholder
 * @property waitTime 리더 획득 대기 시간 — 빈 문자열 시 property 또는 코어 default 폴백
 * @property leaseTime 리더 보유 시간 — 빈 문자열 시 property 또는 코어 default 폴백
 * @property bean 사용할 [io.bluetape4k.leader.LeaderElectionFactory] 빈 이름 (literal only). 빈 문자열 시 default factory
 * @property failureMode 백엔드 예외 처리 정책. default `RETHROW`
 *
 * @see LeaderGroupElection 다중 리더 (semaphore-based) 변형
 * @see LeaderAspectFailureMode failure mode enum
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElection(
    val name: String,
    val waitTime: String = "",
    val leaseTime: String = "",
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
)
