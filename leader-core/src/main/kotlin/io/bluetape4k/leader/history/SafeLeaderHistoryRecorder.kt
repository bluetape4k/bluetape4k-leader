package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.truncateUtf8
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * history sink 오류를 기록하고 election 작업에 전파하지 않는 best-effort recorder입니다.
 * cancellation과 interrupt는 삼키지 않고 전파합니다.
 * @property sink 정리한 record를 저장할 blocking history sink
 */
open class SafeLeaderHistoryRecorder(protected val sink: LeaderHistorySink) {

    companion object: KLogging()

    open fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
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

    open fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
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

    open fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable?) {
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
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Audit sink failed on recordFailed — key=$key" }
        }
    }
}
