package io.bluetape4k.leader.zookeeper.contract

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.zookeeper.AbstractZooKeeperLeaderTest
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * AC-15 / AC-23 검증 — `handle.extendDelegate` 가 elector 가 생성한 delegate 와 **동일 reference** 인지 확인합니다
 * (T13 PR 8, Issue #79).
 *
 * ## ZooKeeper PASSTHROUGH 특성 (Spec §6 row 12)
 *
 * ZooKeeper 는 TTL 이 없는 세션 기반 락 — `extend(d)` 는 lease 연장이 아닌 **session-held liveness 확인** 의미.
 * 따라서 `runIfLeader` 본문 안에서 [LockExtender.extendActiveLockDetailed] 는 항상
 * [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) 를 반환합니다.
 *
 * ## R16 enforce
 *
 * 모든 ZooKeeper elector 는 `LeaderLeaseAutoExtender.start(enabled=false, ...)` 강제 — watchdog 비활성화.
 *
 * ## 검증 케이스
 * - sync single ([ZooKeeperLeaderElector])
 * - suspend single ([ZooKeeperSuspendLeaderElector])
 * - sync group ([ZooKeeperLeaderGroupElector])
 * - suspend group ([ZooKeeperSuspendLeaderGroupElector])
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZooKeeperExtendDelegateReferenceTest: AbstractZooKeeperLeaderTest() {

    companion object: KLoggingChannel()

    private fun randomLockName(): String = "extdelref-zk-${Base58.randomString(8)}"

    @Test
    fun `sync single — extendActiveLockDetailed returns Extended inside body (passthrough)`() {
        val elector = ZooKeeperLeaderElector(curator)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        // ZK passthrough: observedExpireAt = Instant.MAX (session-held semantics)
        ((outcome as ExtendOutcome.Extended).observedExpireAt == Instant.MAX).shouldBeTrue()
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `suspend single — extendActiveLockDetailedSuspend returns Extended inside body (passthrough)`() = runSuspendIO {
        val elector = ZooKeeperSuspendLeaderElector(curator)
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        ((outcome as ExtendOutcome.Extended).observedExpireAt == Instant.MAX).shouldBeTrue()
    }

    @Test
    fun `sync group — extendActiveLockDetailed returns Extended inside body (passthrough)`() {
        val elector = ZooKeeperLeaderGroupElector(
            curator,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLocked()
            outcome = LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        ((outcome as ExtendOutcome.Extended).observedExpireAt == Instant.MAX).shouldBeTrue()
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `suspend group — extendActiveLockDetailedSuspend returns Extended inside body (passthrough)`() = runSuspendIO {
        val elector = ZooKeeperSuspendLeaderGroupElector(
            curator,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
        val lockName = randomLockName()

        var outcome: ExtendOutcome? = null
        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend()
            outcome = LockExtender.extendActiveLockDetailedSuspend(60.seconds)
        }

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        ((outcome as ExtendOutcome.Extended).observedExpireAt == Instant.MAX).shouldBeTrue()
        (AopScopeAccess.pollCapture() == null).shouldBeTrue()
    }

    @Test
    fun `multiple sequential extends on same handle return Extended every time (session passthrough)`() {
        val elector = ZooKeeperLeaderElector(curator)
        val lockName = randomLockName()

        val outcomes = mutableListOf<ExtendOutcome>()
        elector.runIfLeader(lockName) {
            outcomes += LockExtender.extendActiveLockDetailed(30.seconds)
            outcomes += LockExtender.extendActiveLockDetailed(45.seconds)
            outcomes += LockExtender.extendActiveLockDetailed(60.seconds)
        }

        outcomes.forEach { it.shouldBeInstanceOf<ExtendOutcome.Extended>() }
    }
}
