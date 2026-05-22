package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.testcontainers.infra.ConsulServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLeaderGroupElectorIntegrationTest {

    private val consul: ConsulServer by lazy { ConsulServer.Launcher.consul }

    @Test
    fun `runIfLeaderGroup acquires releases and allows sequential reacquire`() {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLocked(lockName)
            LockExtender.extendActiveLock(10.seconds) shouldBeEqualTo true
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `group returns null when all slots are occupied`() {
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
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val holderA = executor.submit<String?> {
                holder.runIfLeader(lockName) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    "holder-a"
                }
            }
            val holderB = executor.submit<String?> {
                holder.runIfLeader(lockName) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    "holder-b"
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
            contender.runIfLeader(lockName) { "contender" }.shouldBeNull()
            holder.state(lockName).activeCount shouldBeEqualTo 2

            release.countDown()
            setOf(holderA.get(10, TimeUnit.SECONDS), holderB.get(10, TimeUnit.SECONDS)) shouldBeEqualTo
                setOf("holder-a", "holder-b")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `slot leader id is written as group audit identity`() {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 2,
                waitTime = 1.seconds,
                leaseTime = 10.seconds,
                nodeId = "consul-group-node-a",
            ),
        )
        val slot = LeaderSlot(lockName = randomName(), leaderId = "group-audit-node-a")

        elector.runIfLeader(slot) {
            val state = elector.state(slot.lockName)

            state.leaders shouldHaveSingleLeader "group-audit-node-a"
            state.leaders.single().nodeId shouldBeEqualTo "consul-group-node-a"
            "ok"
        } shouldBeEqualTo "ok"
    }

    @Test
    fun `runIfLeaderGroup endpoint extension executes action`() {
        endpoint().runIfLeaderGroup(
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
    ): ConsulLeaderGroupElector =
        ConsulLeaderGroupElector(
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
        "leader-group-test:${Base58.randomString(8)}"
}

private infix fun List<io.bluetape4k.leader.LeaderLease>.shouldHaveSingleLeader(auditLeaderId: String) {
    singleOrNull().shouldNotBeNull()
    single().auditLeaderId shouldBeEqualTo auditLeaderId
}
