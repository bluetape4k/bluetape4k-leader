package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * MongoDB suspend group elector 의 per-slot [MongoSuspendLock] (`{lockName}:slot:N`) 용 [ExtendDelegate] —
 * T9 PR 4 (Issue #79).
 *
 * MongoDB coroutine driver 는 reactive native → suspend.
 *
 * ## 동작/계약
 * - [extend] (sync) : `runBlocking` bridge — production sync path 호출 금지
 * - [extendSuspend] : `slotLock.extendDetailed(d)` 직접 호출 (suspend native)
 * - [isHeld] : `runBlocking` bridge
 */
internal class MongoSuspendSlotExtendDelegate(
    private val slotLock: MongoSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { slotLock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend group extend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { slotLock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
