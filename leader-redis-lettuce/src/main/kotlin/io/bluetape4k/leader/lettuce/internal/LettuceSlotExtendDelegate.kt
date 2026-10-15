package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
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
 * `LettuceSlotExtendDelegate`는 Redis Lettuce 그룹 slot/permit의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property group lockName의 slot sorted-set과 metadata hash를 관리하는 그룹 객체입니다.
 * @property token 해당 그룹에서 이 delegate가 소유한 slot token입니다. Redis ownership 확인에 사용되므로 민감한 값입니다.
 */
internal class LettuceSlotExtendDelegate(
    private val group: LettuceSlotTokenGroup,
    private val token: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            group.extendSlot(token, lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Lettuce group extendSlot failed. slotKey=${group.slotKey}, token=$token" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            group.extendSlot(token, lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Lettuce group extendSlotSuspend failed. slotKey=${group.slotKey}, token=$token" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            group.isSlotHeld(token)
        } catch (e: Exception) {
            log.warn(e) { "Lettuce group isSlotHeld failed. slotKey=${group.slotKey}, token=$token" }
            false
        }
}
