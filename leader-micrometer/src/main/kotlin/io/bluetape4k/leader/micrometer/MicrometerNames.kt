package io.bluetape4k.leader.micrometer

// ========= Public constants — importable from leader-spring-boot =========

/**
 * observation과 meter에서 leader 식별자를 담는 tag key입니다.
 */
const val TAG_LEADER_ID: String = "leader.id"

/**
 * leader 식별자의 출처를 담는 low-cardinality tag key입니다.
 */
const val TAG_LEADER_ID_SOURCE: String = "leader.id.source"

/**
 * AOP bridge가 observation을 전달하지 못한 건수를 나타내는 gauge 이름입니다.
 */
const val GAUGE_BRIDGE_DROPPED: String = "leader.aop.bridge.dropped"

/**
 * AOP bridge가 결과를 버린 건수를 나타내는 gauge 이름입니다.
 */
const val GAUGE_BRIDGE_RESULT_DROPPED: String = "leader.aop.bridge.result-dropped"

/**
 * AOP에서 leader ID 해석에 실패한 횟수를 세는 counter 이름입니다.
 */
const val COUNTER_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"

/**
 * AOP lock 획득 observation의 이름입니다.
 */
const val OBSERVATION_LEADER_AOP_ACQUIRE: String = "leader.aop.acquire"

/**
 * AOP 작업 실행 observation의 이름입니다.
 */
const val OBSERVATION_LEADER_AOP_EXECUTION: String = "leader.aop.execution"

/**
 * elected, revoked, skipped 선출 이벤트 observation의 이름입니다.
 */
const val OBSERVATION_LEADER_ELECTION_EVENT: String = "leader.election.event"

/**
 * observation에서 수행한 operation을 나타내는 tag key입니다.
 */
const val OBSERVATION_TAG_OPERATION: String = "leader.operation"

/**
 * observation의 종료 결과를 나타내는 tag key입니다.
 */
const val OBSERVATION_TAG_OUTCOME: String = "outcome"

/**
 * observation의 skip 원인을 나타내는 tag key입니다.
 */
const val OBSERVATION_TAG_REASON: String = "reason"

/**
 * 작업 실패 예외의 단순 클래스명을 담는 tag key입니다.
 */
const val OBSERVATION_TAG_EXCEPTION: String = "exception"

/**
 * leader election 이벤트 종류를 담는 tag key입니다.
 */
const val OBSERVATION_TAG_EVENT: String = "event"

/**
 * lock 획득에 걸린 밀리초를 담는 high-cardinality tag key입니다.
 */
const val OBSERVATION_TAG_ACQUIRE_ELAPSED_MS: String = "acquire.elapsed.ms"

/**
 * 작업 실행에 걸린 밀리초를 담는 high-cardinality tag key입니다.
 */
const val OBSERVATION_TAG_EXECUTION_ELAPSED_MS: String = "execution.elapsed.ms"

/**
 * leader 모듈이 생성하는 meter·observation·tag 이름을 한 곳에서 정의합니다.
 */
internal object MicrometerNames {

    // --- Management action meters ---

    /** management action quarantine 발생 counter입니다. */
    const val MANAGEMENT_QUARANTINE = "bluetape4k.leader.management.quarantine"

    /** management action의 현재 quarantine reservation gauge입니다. */
    const val MANAGEMENT_QUARANTINE_ACTIVE = "bluetape4k.leader.management.quarantine.active"

    /** management quarantine reason tag입니다. */
    const val MANAGEMENT_TAG_REASON = "reason"

    /** management quarantine phase tag입니다. */
    const val MANAGEMENT_TAG_PHASE = "phase"

    /** management quarantine surface tag입니다. */
    const val MANAGEMENT_TAG_SURFACE = "surface"

    // --- Audit exporter meters ---

    /** bounded audit admission 성공 누적 counter입니다. */
    const val AUDIT_EXPORT_ACCEPTED = "leader.audit.export.accepted"

    /** bounded audit admission drop 누적 counter입니다. */
    const val AUDIT_EXPORT_DROPPED = "leader.audit.export.dropped"

    /** bounded audit retry 누적 counter입니다. */
    const val AUDIT_EXPORT_RETRIES = "leader.audit.export.retries"

    /** bounded audit terminal failure 누적 counter입니다. */
    const val AUDIT_EXPORT_FAILURES = "leader.audit.export.failures"

    /** bounded audit queue depth gauge입니다. */
    const val AUDIT_EXPORT_QUEUE_DEPTH = "leader.audit.export.queue.depth"

    /** bounded audit in-flight gauge입니다. */
    const val AUDIT_EXPORT_IN_FLIGHT = "leader.audit.export.in.flight"

    /** bounded audit cancellation 누적 counter입니다. */
    const val AUDIT_EXPORT_CANCELLED = "leader.audit.export.cancelled"

    /** bounded audit executor/scheduler rejection counter입니다. */
    const val AUDIT_EXPORT_REJECTIONS = "leader.audit.export.rejections"

    /** diagnostics observer drop 누적 counter입니다. */
    const val AUDIT_EXPORT_OBSERVER_DROPPED = "leader.audit.export.observer.dropped"

    /** diagnostics observer registration drop 누적 counter입니다. */
    const val AUDIT_EXPORT_OBSERVER_REGISTRATION_DROPPED =
        "leader.audit.export.observer.registration.dropped"

    /** diagnostics worker fatal error 누적 counter입니다. */
    const val AUDIT_EXPORT_DIAGNOSTICS_FAILURES = "leader.audit.export.diagnostics.failures"

    /** diagnostics worker closed 상태 gauge입니다. */
    const val AUDIT_EXPORT_DIAGNOSTICS_CLOSED = "leader.audit.export.diagnostics.closed"

    /** audit metric의 bounded outcome tag key입니다. */
    const val AUDIT_EXPORT_TAG_OUTCOME = "outcome"

    // --- Meter names ---

    /**
     * AOP lock 획득 시도 횟수를 세는 counter 이름입니다.
     */
    const val METER_ATTEMPTS = "leader.aop.attempts"

    /**
     * AOP lock 획득 성공 횟수를 세는 counter 이름입니다.
     */
    const val METER_ACQUIRED = "leader.aop.acquired"

    /**
     * AOP lock 획득 소요 시간을 기록하는 timer 이름입니다.
     */
    const val METER_ACQUIRE_DURATION = "leader.aop.acquire.duration"

    /**
     * lock을 획득하지 못한 결과를 원인별로 세는 counter 이름입니다.
     */
    const val METER_NOT_ACQUIRED = "leader.aop.lock.not.acquired"

    /**
     * leader 작업 실행 시간을 기록하는 timer 이름입니다.
     */
    const val METER_EXECUTION_DURATION = "leader.aop.execution.duration"

    /**
     * leader 작업 실패 횟수를 예외 종류별로 세는 counter 이름입니다.
     */
    const val METER_TASK_FAILED = "leader.aop.task.failed"

    /**
     * 현재 실행 중인 leader 작업 수를 나타내는 gauge 이름입니다.
     */
    const val METER_ACTIVE = "leader.aop.active"

    // --- Tag keys ---

    /**
     * lock 이름을 담는 meter·observation tag key입니다. 동적 값은 sanitization 정책을 거칩니다.
     */
    const val TAG_LOCK_NAME = "lock.name"

    /**
     * lock 미획득 또는 backend connectivity 결과의 bounded reason tag key입니다.
     */
    const val TAG_REASON = "reason"

    /**
     * 작업 실패의 예외 클래스명 tag key입니다.
     */
    const val TAG_EXCEPTION = "exception"

    /**
     * leader election 이벤트 종류를 담는 tag key입니다.
     */
    const val TAG_EVENT = "event"

    /** Backend connectivity 상태 tag입니다. */
    const val TAG_BACKEND_STATUS = "status"

    /** Backend connectivity bounded reason tag입니다. */
    const val TAG_BACKEND_REASON = "reason"

    // --- Sentinel values ---

    /**
     * 예외에 단순 클래스명이 없을 때 사용하는 fallback 문자열입니다.
     */
    const val UNKNOWN_EXCEPTION = "Unknown"

    // --- Decorator meter names ---

    /**
     * decorator가 leader 획득 성공을 세는 legacy counter 이름입니다.
     */
    const val METER_LEADER_ACQUIRED = "shedlock.leader.acquired"

    /**
     * decorator가 leader 미획득 결과를 세는 legacy counter 이름입니다.
     */
    const val METER_LEADER_NOT_ACQUIRED = "shedlock.leader.not_acquired"

    /**
     * decorator가 leader 작업 시간을 기록하는 legacy timer 이름입니다.
     */
    const val METER_LEADER_DURATION = "shedlock.leader.duration"

    /**
     * decorator가 현재 실행 중인 leader 작업 수를 기록하는 legacy gauge 이름입니다.
     */
    const val METER_LEADER_ACTIVE = "shedlock.leader.active"

    /**
     * elected, revoked, skipped leader 이벤트를 세는 counter 이름입니다.
     */
    const val METER_LEADER_EVENTS = "leader.election.events"

    /** Backend connectivity probe 결과를 세는 low-cardinality counter입니다. */
    const val METER_BACKEND_CONNECTIVITY = "leader.backend.connectivity"

    // --- History / Audit meter names ---

    /**
     * history sink 호출에서 예외가 발생한 횟수를 세는 counter 이름입니다.
     */
    const val HISTORY_SINK_FAILURES = "leader.history.sink.failures"

    /**
     * history sink가 acquisition key를 반환하지 않은 횟수를 세는 counter 이름입니다.
     */
    const val HISTORY_ACQUIRE_MISSING = "leader.history.acquire.missing"

    /**
     * MongoDB history index 상태를 나타내는 gauge 이름입니다.
     */
    const val HISTORY_MONGODB_INDEX_STATE = "leader.history.mongodb.index.state"

    /**
     * MongoDB history TTL이 비활성화된 상태를 나타내는 gauge 이름입니다.
     */
    const val HISTORY_MONGODB_TTL_DISABLED = "leader.history.mongodb.ttl.disabled"
}
