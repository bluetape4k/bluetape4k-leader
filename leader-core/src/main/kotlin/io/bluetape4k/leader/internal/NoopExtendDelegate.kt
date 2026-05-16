package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * No-op [ExtendDelegate] for testing — extend/isHeld always return NotHeld / false.
 *
 * Used when creating a synthetic [io.bluetape4k.leader.LeaderLockHandle.Real] in
 * `AopScopeAccess.createSyntheticReal()`. For verifying reentrant peek/push scenarios
 * in unit tests without a real backend.
 */
internal object NoopExtendDelegate : ExtendDelegate {

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.NotHeld

    override fun isHeld(): Boolean = false
}
