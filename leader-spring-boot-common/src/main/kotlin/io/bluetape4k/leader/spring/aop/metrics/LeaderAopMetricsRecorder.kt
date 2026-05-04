package io.bluetape4k.leader.spring.aop.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import kotlin.time.Duration

/**
 * Leader Aspect 6 콜백 SPI — 호출자가 metrics / tracing / 사용자 정의 hook 으로 구현.
 *
 * ## 멀티 빈 주입
 * Aspect 는 등록된 모든 [LeaderAopMetricsRecorder] 빈에 fan-out 호출. Spring `ObjectProvider<List<...>>`
 * 패턴으로 0개 (NoOp) ~ N개 동시 등록 가능.
 *
 * ## isolation
 * 각 recorder 는 `runCatching` 으로 격리됨 — 한 recorder 의 throw 가 leader 본문 또는 다른 recorder 에 영향 없음.
 *
 * ## best-effort
 * `onLockNotAcquired(CONTENTION)` 는 코어 SPI 한계로 본문 null vs 미선출 구분 불가 — 정확한 분리는 후속 [#85].
 *
 * ## 호출 순서
 * - elected: `onLockAttempt` → `onLockAcquired` → `onTaskStarted` → `onTaskFinished`
 * - skipped (CONTENTION): `onLockAttempt` → `onLockNotAcquired(CONTENTION)`
 * - failed: `onLockAttempt` → `onLockAcquired` → `onTaskStarted` → `onTaskFailed`
 * - skipped (BACKEND_ERROR, SKIP 모드): `onLockAttempt` → `onLockNotAcquired(BACKEND_ERROR)`
 *
 * ## Micrometer 연동
 * Micrometer Timer/Counter 통합은 후속 [#75]. 본 PR 은 인터페이스만 노출하고 [NoOp] default 만 등록.
 */
interface LeaderAopMetricsRecorder {

    /** 락 획득 시도 — `pjp.proceed()` 진입 직전 호출. */
    fun onLockAttempt(name: String, options: LeaderElectionOptions) {}

    /** 락 획득 성공 — 본문 진입 직전 호출. [acquireElapsed] = 시도 ~ 획득 경과 시간. */
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {}

    /** 락 미획득 — [reason] 으로 사유 분류. */
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {}

    /** 본문 실행 시작 — `onLockAcquired` 직후 호출. */
    fun onTaskStarted(name: String) {}

    /** 본문 정상 완료. [executionTime] = 시도 ~ 완료 경과. */
    fun onTaskFinished(name: String, executionTime: Duration) {}

    /** 본문 또는 backend 예외. */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {}

    /** No-op 기본 구현 — recorder 빈 미등록 환경에서 fast-path 활성화 위해 사용. */
    object NoOp : LeaderAopMetricsRecorder
}
