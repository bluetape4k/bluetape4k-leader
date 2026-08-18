package io.bluetape4k.leader.audit

/**
 * exporter의 bounded queue, in-flight와 누적 lifecycle counter snapshot입니다.
 *
 * snapshot은 동적 lock name, node id, endpoint, error message를 포함하지 않습니다.
 */
class LeaderAuditExportSnapshot private constructor(
    val queued: Int,
    val inFlight: Int,
    val scheduledRetries: Int,
    val admitted: Int,
    val accepted: Long,
    val droppedQueueFull: Long,
    val droppedClosed: Long,
    val retries: Long,
    val terminalFailures: Long,
    val cancellations: Long,
    val executorRejections: Long,
    val schedulerRejections: Long,
    val observerDrops: Long,
    val observerRegistrationDrops: Long,
    val diagnosticsFatalErrors: Long,
    val diagnosticsClosed: Boolean,
    val closed: Boolean,
) {

    companion object {
        /** 내부 counter state를 immutable public snapshot으로 만듭니다. */
        @JvmSynthetic
        internal fun create(
            queued: Int,
            inFlight: Int,
            scheduledRetries: Int,
            admitted: Int,
            accepted: Long,
            droppedQueueFull: Long,
            droppedClosed: Long,
            retries: Long,
            terminalFailures: Long,
            cancellations: Long,
            executorRejections: Long,
            schedulerRejections: Long,
            observerDrops: Long,
            observerRegistrationDrops: Long,
            diagnosticsFatalErrors: Long,
            diagnosticsClosed: Boolean,
            closed: Boolean,
        ): LeaderAuditExportSnapshot = LeaderAuditExportSnapshot(
            queued = queued,
            inFlight = inFlight,
            scheduledRetries = scheduledRetries,
            admitted = admitted,
            accepted = accepted,
            droppedQueueFull = droppedQueueFull,
            droppedClosed = droppedClosed,
            retries = retries,
            terminalFailures = terminalFailures,
            cancellations = cancellations,
            executorRejections = executorRejections,
            schedulerRejections = schedulerRejections,
            observerDrops = observerDrops,
            observerRegistrationDrops = observerRegistrationDrops,
            diagnosticsFatalErrors = diagnosticsFatalErrors,
            diagnosticsClosed = diagnosticsClosed,
            closed = closed,
        )
    }
}
