package io.bluetape4k.leader.micrometer

/**
 * leader-aop Micrometer 메터/태그 이름 상수.
 *
 * 모든 메터 이름은 `leader.aop.` prefix를 공유한다.
 * Micrometer의 [io.micrometer.core.instrument.config.NamingConvention]이 백엔드별로 자동 변환한다
 * (Prometheus: `leader_aop_attempts_total`, Datadog: `leader.aop.attempts.count` 등).
 */
internal object MicrometerNames {

    // --- Meter names ---

    /** 락 획득 시도 횟수 Counter. */
    const val METER_ATTEMPTS = "leader.aop.attempts"

    /** 락 획득 성공(leader 선출) Counter. */
    const val METER_ACQUIRED = "leader.aop.acquired"

    /** 락 미획득 Counter. [TAG_REASON] 태그로 사유 구분. */
    const val METER_NOT_ACQUIRED = "leader.aop.lock.not.acquired"

    /** 정상 완료된 작업의 실행 시간 Timer. */
    const val METER_EXECUTION_DURATION = "leader.aop.execution.duration"

    /** 작업 본문 예외 발생 Counter. [TAG_EXCEPTION] 태그로 예외 유형 구분. */
    const val METER_TASK_FAILED = "leader.aop.task.failed"

    /**
     * 현재 실행 중인 leader 작업 수 Gauge.
     *
     * **JVM-local 값** — 멀티 인스턴스 클러스터에서 Prometheus 집계 시
     * `sum` 대신 `max by (lock_name) (leader_aop_active)` 사용을 권장한다.
     */
    const val METER_ACTIVE = "leader.aop.active"

    // --- Tag keys ---

    /** SpEL로 결정된 락 이름 태그. */
    const val TAG_LOCK_NAME = "lock.name"

    /** 락 미획득 사유 태그 (`CONTENTION` / `BACKEND_ERROR`). */
    const val TAG_REASON = "reason"

    /** 작업 실패 예외 유형 태그 (`throwable::class.simpleName`). */
    const val TAG_EXCEPTION = "exception"

    // --- Sentinel values ---

    /** 익명 클래스 등 `simpleName == null` 인 경우 [TAG_EXCEPTION] 태그 대체값. */
    const val UNKNOWN_EXCEPTION = "Unknown"
}
