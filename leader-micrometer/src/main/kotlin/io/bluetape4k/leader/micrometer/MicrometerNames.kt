package io.bluetape4k.leader.micrometer

// ========= Public constants — importable from leader-spring-boot =========

/**
 * Micrometer tag key for the elected leader's audit identity.
 *
 * ## Contract
 * Tag value is the [io.bluetape4k.leader.LeaderSlot.leaderId] resolved by the aspect.
 * For `AUTO` source, this is a random Base58 string per call — high cardinality.
 *
 * ## Usage
 * ```kotlin
 * registry.counter("leader.aop.acquired", TAG_LEADER_ID, leaderId).increment()
 * ```
 */
const val TAG_LEADER_ID: String = "leader.id"

/**
 * Micrometer tag key for the provenance of the elected leader's identity.
 *
 * ## Contract
 * Tag value is one of: `LITERAL`, `SPEL`, `PROPERTY`, `AUTO`.
 * Bounded cardinality (4 values) — safe for all Micrometer backends.
 */
const val TAG_LEADER_ID_SOURCE: String = "leader.id.source"

/**
 * Gauge name for the count of bridge-dropped slot elections.
 *
 * ## Contract
 * Incremented by [io.bluetape4k.leader.identity.LeaderElectorBridgeLog] when a backend
 * falls through to the bridge default instead of overriding the slot variant.
 */
const val GAUGE_BRIDGE_DROPPED: String = "leader.aop.bridge.dropped"

/**
 * Gauge name for the count of bridge-dropped result elections.
 *
 * ## Contract
 * Incremented by [io.bluetape4k.leader.identity.LeaderElectorBridgeLog] when the result
 * variant bridge default is used instead of a backend override.
 */
const val GAUGE_BRIDGE_RESULT_DROPPED: String = "leader.aop.bridge.result-dropped"

/**
 * Counter name for leader ID resolution failures.
 *
 * ## Contract
 * Single source of truth: delegates to [io.bluetape4k.leader.metrics.LeaderMetricNames.METRIC_LEADER_ID_RESOLUTION_FAILED].
 * Incremented from the PR7 aspect on every [io.bluetape4k.leader.identity.LeaderIdResolutionException].
 *
 * ```kotlin
 * registry.counter(COUNTER_LEADER_ID_RESOLUTION_FAILED).increment()
 * ```
 */
const val COUNTER_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"

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

    /** 리더 선출 lifecycle 이벤트 태그 (`elected` / `revoked` / `skipped`). */
    const val TAG_EVENT = "event"

    // --- Sentinel values ---

    /** 익명 클래스 등 `simpleName == null` 인 경우 [TAG_EXCEPTION] 태그 대체값. */
    const val UNKNOWN_EXCEPTION = "Unknown"

    // --- Decorator meter names ---

    /** 데코레이터 기반 리더 선출 성공 Counter. */
    const val METER_LEADER_ACQUIRED = "shedlock.leader.acquired"

    /** 데코레이터 기반 리더 미획득 Counter. */
    const val METER_LEADER_NOT_ACQUIRED = "shedlock.leader.not_acquired"

    /** 데코레이터 기반 리더 작업 실행 시간 Timer. */
    const val METER_LEADER_DURATION = "shedlock.leader.duration"

    /** 데코레이터 기반 현재 실행 중인 리더 작업 수 Gauge. */
    const val METER_LEADER_ACTIVE = "shedlock.leader.active"

    /** listener 기반 리더 선출 lifecycle 이벤트 Counter. */
    const val METER_LEADER_EVENTS = "leader.election.events"

    // --- History / Audit meter names ---

    /** Counter: history sink call failures (any Exception). Tag: `sink`. */
    const val HISTORY_SINK_FAILURES = "leader.history.sink.failures"

    /** Counter: recordAcquired returned null (storage unavailable or duplicate). Tag: `sink`. */
    const val HISTORY_ACQUIRE_MISSING = "leader.history.acquire.missing"

    /** Gauge: MongoDB TTL index presence state (1 = present, 0 = absent). */
    const val HISTORY_MONGODB_INDEX_STATE = "leader.history.mongodb.index.state"

    /** Counter: MongoDB TTL index was disabled at startup (misconfiguration signal). */
    const val HISTORY_MONGODB_TTL_DISABLED = "leader.history.mongodb.ttl.disabled"
}
