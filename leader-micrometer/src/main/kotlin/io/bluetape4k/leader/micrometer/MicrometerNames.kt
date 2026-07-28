package io.bluetape4k.leader.micrometer

// ========= Public constants — importable from leader-spring-boot =========

/**
 * `TAG_LEADER_ID` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val TAG_LEADER_ID: String = "leader.id"

/**
 * `TAG_LEADER_ID_SOURCE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val TAG_LEADER_ID_SOURCE: String = "leader.id.source"

/**
 * `GAUGE_BRIDGE_DROPPED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val GAUGE_BRIDGE_DROPPED: String = "leader.aop.bridge.dropped"

/**
 * `GAUGE_BRIDGE_RESULT_DROPPED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val GAUGE_BRIDGE_RESULT_DROPPED: String = "leader.aop.bridge.result-dropped"

/**
 * `COUNTER_LEADER_ID_RESOLUTION_FAILED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val COUNTER_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"

/**
 * `OBSERVATION_LEADER_AOP_ACQUIRE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_LEADER_AOP_ACQUIRE: String = "leader.aop.acquire"

/**
 * `OBSERVATION_LEADER_AOP_EXECUTION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_LEADER_AOP_EXECUTION: String = "leader.aop.execution"

/**
 * `OBSERVATION_LEADER_ELECTION_EVENT` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_LEADER_ELECTION_EVENT: String = "leader.election.event"

/**
 * `OBSERVATION_TAG_OPERATION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_OPERATION: String = "leader.operation"

/**
 * `OBSERVATION_TAG_OUTCOME` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_OUTCOME: String = "outcome"

/**
 * `OBSERVATION_TAG_REASON` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_REASON: String = "reason"

/**
 * `OBSERVATION_TAG_EXCEPTION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_EXCEPTION: String = "exception"

/**
 * `OBSERVATION_TAG_EVENT` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_EVENT: String = "event"

/**
 * `OBSERVATION_TAG_ACQUIRE_ELAPSED_MS` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_ACQUIRE_ELAPSED_MS: String = "acquire.elapsed.ms"

/**
 * `OBSERVATION_TAG_EXECUTION_ELAPSED_MS` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
const val OBSERVATION_TAG_EXECUTION_ELAPSED_MS: String = "execution.elapsed.ms"

/**
 * `MicrometerNames`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
internal object MicrometerNames {

    // --- Meter names ---

    /**
     * `METER_ATTEMPTS` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_ATTEMPTS = "leader.aop.attempts"

    /**
     * `METER_ACQUIRED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_ACQUIRED = "leader.aop.acquired"

    /**
     * `METER_ACQUIRE_DURATION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_ACQUIRE_DURATION = "leader.aop.acquire.duration"

    /**
     * `METER_NOT_ACQUIRED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_NOT_ACQUIRED = "leader.aop.lock.not.acquired"

    /**
     * `METER_EXECUTION_DURATION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_EXECUTION_DURATION = "leader.aop.execution.duration"

    /**
     * `METER_TASK_FAILED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_TASK_FAILED = "leader.aop.task.failed"

    /**
     * `METER_ACTIVE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_ACTIVE = "leader.aop.active"

    // --- Tag keys ---

    /**
     * `TAG_LOCK_NAME` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val TAG_LOCK_NAME = "lock.name"

    /**
     * `TAG_REASON` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val TAG_REASON = "reason"

    /**
     * `TAG_EXCEPTION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val TAG_EXCEPTION = "exception"

    /**
     * `TAG_EVENT` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val TAG_EVENT = "event"

    // --- Sentinel values ---

    /**
     * `UNKNOWN_EXCEPTION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val UNKNOWN_EXCEPTION = "Unknown"

    // --- Decorator meter names ---

    /**
     * `METER_LEADER_ACQUIRED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_LEADER_ACQUIRED = "shedlock.leader.acquired"

    /**
     * `METER_LEADER_NOT_ACQUIRED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_LEADER_NOT_ACQUIRED = "shedlock.leader.not_acquired"

    /**
     * `METER_LEADER_DURATION` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_LEADER_DURATION = "shedlock.leader.duration"

    /**
     * `METER_LEADER_ACTIVE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_LEADER_ACTIVE = "shedlock.leader.active"

    /**
     * `METER_LEADER_EVENTS` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val METER_LEADER_EVENTS = "leader.election.events"

    // --- History / Audit meter names ---

    /**
     * `HISTORY_SINK_FAILURES` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val HISTORY_SINK_FAILURES = "leader.history.sink.failures"

    /**
     * `HISTORY_ACQUIRE_MISSING` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val HISTORY_ACQUIRE_MISSING = "leader.history.acquire.missing"

    /**
     * `HISTORY_MONGODB_INDEX_STATE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val HISTORY_MONGODB_INDEX_STATE = "leader.history.mongodb.index.state"

    /**
     * `HISTORY_MONGODB_TTL_DISABLED` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val HISTORY_MONGODB_TTL_DISABLED = "leader.history.mongodb.ttl.disabled"
}
