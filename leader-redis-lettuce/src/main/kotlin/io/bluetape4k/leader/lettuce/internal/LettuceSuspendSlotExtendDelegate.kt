package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for the suspend variant of [LettuceSlotTokenGroup] — T7 PR 2 / AC-16.
 *
 * The Lettuce async API is Netty event-loop-based non-blocking → suspend native.
 */
internal class LettuceSuspendSlotExtendDelegate(
    private val group: LettuceSlotTokenGroup,
    private val token: String,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { group.extendSlotSuspending(token, lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend group extendSlot failed. slotKey=${group.slotKey}, token=$token" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            group.extendSlotSuspending(token, lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend group extendSlotSuspend failed. slotKey=${group.slotKey}, token=$token" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { group.isSlotHeldSuspending(token) }
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend group isSlotHeld failed. slotKey=${group.slotKey}, token=$token" }
            false
        }
}
