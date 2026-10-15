package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `MongoSuspendSlotExtendDelegate`는 MongoDB 그룹 slot/permit의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property slotLock suspend 그룹에서 배정된 한 slot key의 MongoDB lease 연장과 보유 여부를 위임할 lock입니다.
 */
internal class MongoSuspendSlotExtendDelegate(
    private val slotLock: MongoSuspendLock,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
