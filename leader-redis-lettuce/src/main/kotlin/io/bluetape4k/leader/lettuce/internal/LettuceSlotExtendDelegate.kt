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
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * [ExtendDelegate] for [LettuceSlotTokenGroup] (sync group, server-side TIME Lua) — T7 PR 2 / AC-16.
 *
 * ## Behavior / Contract
 * - [extend]: Delegates to `group.extendSlot(token, d)`. Uses server-side `redis.call('TIME')` to eliminate client clock skew.
 * - [extendSuspend]: Blocking sync facade — wrapped with `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld]: Delegates to `group.isSlotHeld(token)` (server-side TIME Lua).
 */
internal class LettuceSlotExtendDelegate(
    private val group: LettuceSlotTokenGroup,
    private val token: String,
) : ExtendDelegate {

    companion object : KLogging()

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
