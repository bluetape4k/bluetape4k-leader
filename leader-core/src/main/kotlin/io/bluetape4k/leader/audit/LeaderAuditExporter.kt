package io.bluetape4k.leader.audit

/**
 * sanitized audit event를 bounded delivery pipeline에 제출하는 public exporter 계약입니다.
 *
 * `submit`은 delivery 완료를 기다리지 않으며, `ACCEPTED`는 admission만 의미합니다.
 * queue가 가득 찼거나 exporter가 닫힌 경우에는 예외 대신 명시적인 drop 결과를 반환합니다.
 */
interface LeaderAuditExporter : AutoCloseable {

    /** event를 bounded pipeline에 non-blocking admission합니다. */
    fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult

    /** 유한 lifecycle observation을 비동기로 받을 observer를 등록합니다. */
    fun observe(observer: LeaderAuditExportObserver): AutoCloseable

    /** 현재 exporter counter와 lifecycle의 O(1) snapshot을 반환합니다. */
    fun snapshot(): LeaderAuditExportSnapshot

    /** exporter를 idempotently 닫고 queued/retry/in-flight work를 취소합니다. */
    override fun close()
}

/**
 * exporter admission 결과입니다.
 */
enum class LeaderAuditSubmitResult {
    /** work가 pipeline permit을 획득했습니다. delivery 성공을 의미하지 않습니다. */
    ACCEPTED,

    /** bounded admission capacity가 소진되었습니다. */
    DROPPED_QUEUE_FULL,

    /** exporter가 닫힌 뒤 제출되었습니다. */
    DROPPED_CLOSED,
}

/**
 * exporter lifecycle을 외부에 전달하는 유한 observation 종류입니다.
 */
enum class LeaderAuditExportObservation {
    ACCEPTED,
    DROPPED_QUEUE_FULL,
    DROPPED_CLOSED,
    RETRY,
    TERMINAL_FAILURE,
    CANCELLED,
    EXECUTOR_REJECTED,
    SCHEDULER_REJECTED,
}

/**
 * delivery/admission lifecycle을 비동기로 받는 callback입니다.
 */
fun interface LeaderAuditExportObserver {

    /** observer callback입니다. 일반 `Exception`은 exporter가 격리합니다. */
    fun onObservation(observation: LeaderAuditExportObservation)
}
