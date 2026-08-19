package io.bluetape4k.leader.audit

import java.util.concurrent.CompletableFuture

/**
 * 하나의 sanitized event delivery를 시작하는 callback입니다.
 *
 * 반환 future는 exporter가 timeout/close 시 취소할 수 있으므로 caller는 해당 ownership을
 * 보존해야 합니다. callback이 동기적으로 예외를 던지는 경우 exporter가 retry/terminalize합니다.
 */
fun interface LeaderAuditDelivery {

    /** delivery를 시작하고 cancellation-capable future를 반환합니다. */
    fun deliver(event: LeaderAuditExportEvent): CompletableFuture<LeaderAuditDeliveryResult>
}

/**
 * 하나의 delivery attempt 결과입니다.
 */
enum class LeaderAuditDeliveryResult {
    SUCCESS,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
}
