package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.testcontainers.infra.ConsulServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulSuspendLeaderElectorIntegrationTest {

    private val consul: ConsulServer by lazy { ConsulServer.Launcher.consul }

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend(lockName)
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `runIfLeader returns null on contention`() = runSuspendIO {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
        )
        val lockName = randomName()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holderJob = async {
            holder.runIfLeader(lockName) {
                started.complete(Unit)
                release.await()
                "holder"
            }
        }

        started.await()
        contender.runIfLeader(lockName) { "contender" }.shouldBeNull()

        release.complete(Unit)
        holderJob.await() shouldBeEqualTo "holder"
    }

    @Test
    fun `slot leader id is written as audit identity`() = runSuspendIO {
        val elector = newElector(
            leaderOptions = LeaderElectionOptions(
                waitTime = 1.seconds,
                leaseTime = 10.seconds,
                nodeId = "consul-suspend-node-a",
            ),
        )
        val slot = LeaderSlot(lockName = randomName(), leaderId = "suspend-audit-node-a")

        elector.runIfLeader(slot) {
            val state = elector.state(slot.lockName)

            state.leader.shouldNotBeNull()
            state.leader?.auditLeaderId shouldBeEqualTo "suspend-audit-node-a"
            state.leader?.nodeId shouldBeEqualTo "consul-suspend-node-a"
            "ok"
        } shouldBeEqualTo "ok"
    }

    @Test
    fun `cancellation releases lease for next suspend attempt`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(100.milliseconds) {
                elector.runIfLeader(lockName) {
                    delay(10.seconds)
                }
            }
        }

        elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @Test
    fun `action failure releases lease for next suspend attempt`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        assertFailsWith<LeaderElectionException> {
            elector.runIfLeader(lockName) {
                throw LeaderElectionException("action failed")
            }
        }

        elector.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
    }

    @Test
    fun `extendActiveLockSuspend works inside elected body`() = runSuspendIO {
        val elector = newElector()

        val extended = elector.runIfLeader(randomName()) {
            LockExtender.extendActiveLockSuspend(10.seconds)
        }

        extended shouldBeEqualTo true
    }

    @Test
    fun `suspendRunIfLeader extension executes elected action`() = runSuspendIO {
        endpoint().suspendRunIfLeader(
            lockName = randomName(),
            options = ConsulLeaderElectionOptions(
                keyPrefix = keyPrefix(),
                leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 10.seconds),
            ),
        ) {
            "extension"
        } shouldBeEqualTo "extension"
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 10.seconds),
    ): ConsulSuspendLeaderElector =
        ConsulSuspendLeaderElector(
            endpoint(),
            ConsulLeaderElectionOptions(
                keyPrefix = keyPrefix,
                leaderOptions = leaderOptions,
            ),
        )

    private fun endpoint(): ConsulEndpoint =
        ConsulEndpoint(consul.url)

    private fun keyPrefix(): String =
        "bluetape4k/leader/test/${Base58.randomString(8)}"

    private fun randomName(): String =
        "leader-test:${Base58.randomString(8)}"
}
