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
 * Micrometer meter and tag name constants for leader-aop.
 *
 * All meter names share the `leader.aop.` prefix.
 * Micrometer's [io.micrometer.core.instrument.config.NamingConvention] automatically converts them
 * per backend (e.g., Prometheus: `leader_aop_attempts_total`, Datadog: `leader.aop.attempts.count`).
 */
internal object MicrometerNames {

    // --- Meter names ---

    /** Counter for the number of lock acquisition attempts. */
    const val METER_ATTEMPTS = "leader.aop.attempts"

    /** Counter for successful lock acquisitions (leader elected). */
    const val METER_ACQUIRED = "leader.aop.acquired"

    /** Counter for failed lock acquisitions. Reason distinguished by [TAG_REASON] tag. */
    const val METER_NOT_ACQUIRED = "leader.aop.lock.not.acquired"

    /** Timer for execution duration of successfully completed tasks. */
    const val METER_EXECUTION_DURATION = "leader.aop.execution.duration"

    /** Counter for exceptions thrown from the task body. Exception type distinguished by [TAG_EXCEPTION] tag. */
    const val METER_TASK_FAILED = "leader.aop.task.failed"

    /**
     * Gauge for the number of currently running leader tasks.
     *
     * **JVM-local value** — when aggregating in Prometheus across a multi-instance cluster,
     * prefer `max by (lock_name) (leader_aop_active)` over `sum`.
     */
    const val METER_ACTIVE = "leader.aop.active"

    // --- Tag keys ---

    /** Tag for the lock name resolved via SpEL. */
    const val TAG_LOCK_NAME = "lock.name"

    /** Tag for the lock acquisition failure reason (`CONTENTION` / `BACKEND_ERROR`). */
    const val TAG_REASON = "reason"

    /** Tag for the exception type on task failure (`throwable::class.simpleName`). */
    const val TAG_EXCEPTION = "exception"

    /** Tag for leader election lifecycle events (`elected` / `revoked` / `skipped`). */
    const val TAG_EVENT = "event"

    // --- Sentinel values ---

    /** Fallback value for the [TAG_EXCEPTION] tag when `simpleName == null` (e.g., anonymous classes). */
    const val UNKNOWN_EXCEPTION = "Unknown"

    // --- Decorator meter names ---

    /** Counter for successful leader elections via decorator. */
    const val METER_LEADER_ACQUIRED = "shedlock.leader.acquired"

    /** Counter for failed leader acquisitions via decorator. */
    const val METER_LEADER_NOT_ACQUIRED = "shedlock.leader.not_acquired"

    /** Timer for leader task execution duration via decorator. */
    const val METER_LEADER_DURATION = "shedlock.leader.duration"

    /** Gauge for the number of currently running leader tasks via decorator. */
    const val METER_LEADER_ACTIVE = "shedlock.leader.active"

    /** Counter for listener-based leader election lifecycle events. */
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
