package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `LettuceSuspendSlotExtendDelegate`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property group Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property token Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class LettuceSuspendSlotExtendDelegate(
    private val group: LettuceSlotTokenGroup,
    private val token: String,
) : SuspendExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            group.extendSlotSuspending(token, lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend group extendSlotSuspend failed. slotKey=${group.slotKey}, token=$token" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            group.isSlotHeldSuspending(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend group isSlotHeld failed. slotKey=${group.slotKey}, token=$token" }
            false
        }
}
