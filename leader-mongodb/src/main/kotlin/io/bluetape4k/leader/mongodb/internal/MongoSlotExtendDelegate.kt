package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `MongoSlotExtendDelegate`는 MongoDB 그룹 slot/permit의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property slotLock 그룹에서 배정된 한 slot key의 MongoDB lease 연장과 보유 여부를 위임할 lock입니다.
 */
internal class MongoSlotExtendDelegate(
    private val slotLock: MongoLock,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "MongoDB group extend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoDB group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "MongoDB group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
