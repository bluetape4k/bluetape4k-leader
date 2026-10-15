package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * lock handle이 extension 기능을 갖지 않을 때 사용하는 delegate입니다. 연장은 항상 `NotHeld`이고 소유 여부는 항상 `false`입니다.
 */
internal object NoopExtendDelegate: ExtendDelegate {

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.NotHeld

    override fun isHeld(): Boolean = false
}
