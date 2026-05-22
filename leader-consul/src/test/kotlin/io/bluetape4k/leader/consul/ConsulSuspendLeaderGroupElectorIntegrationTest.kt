package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.testcontainers.infra.ConsulServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulSuspendLeaderGroupElectorIntegrationTest {

    private val consul: ConsulServer by lazy { ConsulServer.Launcher.consul }

    @Test
    fun `runIfLeaderGroup acquires releases and allows sequential reacquire`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend(lockName)
            LockExtender.extendActiveLockSuspend(10.seconds) shouldBeEqualTo true
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `group returns null when all slots are occupied`() = runSuspendIO {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 2.seconds, leaseTime = 10.seconds),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 200.milliseconds, leaseTime = 10.seconds),
        )
        val lockName = randomName()
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holderA = async {
            holder.runIfLeader(lockName) {
                startedA.complete(Unit)
                release.await()
                "holder-a"
            }
        }
        val holderB = async {
            holder.runIfLeader(lockName) {
                startedB.complete(Unit)
                release.await()
                "holder-b"
            }
        }

        startedA.await()
        startedB.await()
        contender.runIfLeader(lockName) { "contender" }.shouldBeNull()
        holder.state(lockName).activeCount shouldBeEqualTo 2

        release.complete(Unit)
        setOf(holderA.await(), holderB.await()) shouldBeEqualTo setOf("holder-a", "holder-b")
    }

    @Test
    fun `slot leader id is written as group audit identity`() = runSuspendIO {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 2,
                waitTime = 1.seconds,
                leaseTime = 10.seconds,
                nodeId = "consul-suspend-group-node-a",
            ),
        )
        val slot = LeaderSlot(lockName = randomName(), leaderId = "suspend-group-audit-node-a")

        elector.runIfLeader(slot) {
            val state = elector.state(slot.lockName)

            state.leaders.singleOrNull().shouldNotBeNull()
            state.leaders.single().auditLeaderId shouldBeEqualTo "suspend-group-audit-node-a"
            state.leaders.single().nodeId shouldBeEqualTo "consul-suspend-group-node-a"
            "ok"
        } shouldBeEqualTo "ok"
    }

    @Test
    fun `suspendRunIfLeaderGroup endpoint extension executes action`() = runSuspendIO {
        endpoint().suspendRunIfLeaderGroup(
            lockName = randomName(),
            options = ConsulLeaderGroupElectionOptions(
                keyPrefix = keyPrefix(),
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 10.seconds),
            ),
        ) {
            "extension"
        } shouldBeEqualTo "extension"
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        groupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 10.seconds),
    ): ConsulSuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(
            endpoint(),
            ConsulLeaderGroupElectionOptions(
                keyPrefix = keyPrefix,
                leaderGroupOptions = groupOptions,
            ),
        )

    private fun endpoint(): ConsulEndpoint =
        ConsulEndpoint(consul.url)

    private fun keyPrefix(): String =
        "bluetape4k/leader/test/${Base58.randomString(8)}"

    private fun randomName(): String =
        "leader-suspend-group-test:${Base58.randomString(8)}"
}
