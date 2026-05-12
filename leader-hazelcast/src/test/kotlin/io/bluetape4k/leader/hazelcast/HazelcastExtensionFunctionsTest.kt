package io.bluetape4k.leader.hazelcast

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HazelcastExtensionFunctionsTest : AbstractHazelcastLeaderTest() {

    companion object : KLogging()

    // ─── HazelcastLeaderElectorFactory ──────────────────────────────────────

    @Test
    fun `HazelcastLeaderElectorFactory - create 는 LeaderElector 인스턴스를 반환한다`() {
        val factory = HazelcastLeaderElectorFactory(hazelcastClient)
        val elector = factory.create(LeaderElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<LeaderElector>()
        elector.shouldBeInstanceOf<HazelcastLeaderElector>()
    }

    @Test
    fun `HazelcastLeaderGroupElectorFactory - create 는 LeaderGroupElector 인스턴스를 반환한다`() {
        val factory = HazelcastLeaderGroupElectorFactory(hazelcastClient)
        val elector = factory.create(LeaderGroupElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<LeaderGroupElector>()
        elector.shouldBeInstanceOf<HazelcastLeaderGroupElector>()
    }

    // ─── HazelcastInstance.runIfLeader 확장 함수 ──────────────────────────────

    @Test
    fun `HazelcastInstance runIfLeader - 리더로 선출되어 action 을 실행한다`() {
        val result = hazelcastClient.runIfLeader(randomName()) { "done" }
        result shouldBeEqualTo "done"
    }

    @Test
    fun `HazelcastInstance runIfLeader - lock 이 보유된 경우 null 을 반환한다`() {
        val lockName = randomName()
        val shortWait = LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 3.seconds)
        val holder = HazelcastLeaderElector(hazelcastClient, shortWait)

        holder.runIfLeader(lockName) {
            val result = hazelcastClient.runIfLeader(lockName, shortWait) { "acquired" }
            result.shouldBeNull()
        }
    }

    @Test
    fun `HazelcastInstance runAsyncIfLeader - 리더로 선출되어 action 을 실행한다`() {
        val future = hazelcastClient.runAsyncIfLeader(randomName()) {
            java.util.concurrent.CompletableFuture.completedFuture("async-done")
        }
        future.get(5, java.util.concurrent.TimeUnit.SECONDS) shouldBeEqualTo "async-done"
    }

    // ─── HazelcastInstance.suspendRunIfLeader 확장 함수 ────────────────────

    @Test
    fun `HazelcastInstance suspendRunIfLeader - 리더로 선출되어 suspend action 을 실행한다`() = runTest {
        val result = hazelcastClient.suspendRunIfLeader(randomName()) { "suspend-done" }
        result shouldBeEqualTo "suspend-done"
    }

    @Test
    fun `HazelcastInstance suspendRunIfLeader - lock 이 보유된 경우 null 을 반환한다`() = runTest {
        val lockName = randomName()
        val shortWait = LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 3.seconds)
        val holder = HazelcastSuspendLeaderElector(hazelcastClient, shortWait)

        holder.runIfLeader(lockName) {
            val result = hazelcastClient.suspendRunIfLeader(lockName, shortWait) { "acquired" }
            result.shouldBeNull()
        }
    }

    // ─── HazelcastInstance.runIfLeaderGroup 확장 함수 ────────────────────────

    @Test
    fun `HazelcastInstance runIfLeaderGroup - 리더 그룹에 선출되어 action 을 실행한다`() {
        val result = hazelcastClient.runIfLeaderGroup(randomName()) { "group-done" }
        result shouldBeEqualTo "group-done"
    }

    @Test
    fun `HazelcastInstance runIfLeaderGroup - maxLeaders 초과 시 null 반환`() {
        val lockName = randomName()
        val opts = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 50.milliseconds, leaseTime = 3.seconds)
        val holder = HazelcastLeaderGroupElector(hazelcastClient, opts)

        holder.runIfLeader(lockName) {
            val result = hazelcastClient.runIfLeaderGroup(lockName, opts) { "acquired" }
            result.shouldBeNull()
        }
    }

    // ─── HazelcastInstance.suspendRunIfLeaderGroup 확장 함수 ─────────────────

    @Test
    fun `HazelcastInstance suspendRunIfLeaderGroup - 리더 그룹에 선출되어 suspend action 을 실행한다`() = runTest {
        val result = hazelcastClient.suspendRunIfLeaderGroup(randomName()) { "suspend-group-done" }
        result shouldBeEqualTo "suspend-group-done"
    }

    @Test
    fun `HazelcastInstance suspendRunIfLeaderGroup - maxLeaders 초과 시 null 반환`() = runTest {
        val lockName = randomName()
        val opts = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 50.milliseconds, leaseTime = 3.seconds)
        val holder = HazelcastSuspendLeaderGroupElector(hazelcastClient, opts)

        holder.runIfLeader(lockName) {
            val result = hazelcastClient.suspendRunIfLeaderGroup(lockName, opts) { "acquired" }
            result.shouldBeNull()
        }
    }
}
