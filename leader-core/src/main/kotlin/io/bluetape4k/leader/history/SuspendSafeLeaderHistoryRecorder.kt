package io.bluetape4k.leader.history

import io.bluetape4k.support.truncateUtf8
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * `SuspendSafeLeaderHistoryRecorder`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property sink `sink` 호출 또는 상태 계산에 필요한 값입니다.
 */
open class SuspendSafeLeaderHistoryRecorder(protected val sink: SuspendLeaderHistorySink) {

    companion object : KLogging()

    open suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        return try {
            sink.recordAcquired(sanitize(record))
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordAcquired — lockName=${record.lockName}" }
            null
        }
    }

    open suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            sink.recordCompleted(key, finishedAt, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordCompleted — key=$key" }
        }
    }

    open suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable?) {
        if (error is CancellationException) {
            log.warn { "Leader action was cancelled — recording as FAILED best-effort: key=$key" }
        }
        val errorType = error?.let { it::class.qualifiedName ?: it.javaClass.name }
        val errorMessage = error?.message?.sanitizeForLog()
            ?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES)
        try {
            sink.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            // best-effort: blocking IO를 수행하는 sink 구현은 runInterruptible {}을 사용해야 합니다.
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordFailed — key=$key" }
        }
    }
}
