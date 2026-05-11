package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * 테스트용 no-op [ExtendDelegate] — extend/isHeld 가 항상 NotHeld / false 반환.
 *
 * `AopScopeAccess.createSyntheticReal()` 에서 synthetic [io.bluetape4k.leader.LeaderLockHandle.Real]
 * 생성 시 사용합니다. 실제 backend 없는 단위 테스트에서 reentrant peek/push 시나리오 검증용.
 */
internal object NoopExtendDelegate : ExtendDelegate {

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.NotHeld

    override fun isHeld(): Boolean = false
}
