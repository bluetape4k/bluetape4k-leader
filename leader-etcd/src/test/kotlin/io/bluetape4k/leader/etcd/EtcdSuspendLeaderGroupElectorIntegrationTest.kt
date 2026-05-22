package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EtcdSuspendLeaderGroupElectorIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `runIfLeader allows max leaders and skips next contender`() = runSuspendIO {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val holder = EtcdSuspendLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 5.seconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val contender = EtcdSuspendLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 250.milliseconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val lockName = randomName()
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val first = async {
                holder.runIfLeader(lockName) {
                    LockAssert.assertLockedSuspend(lockName)
                    firstStarted.complete(Unit)
                    release.await()
                    "first"
                }
            }
            val second = async {
                holder.runIfLeader(lockName) {
                    LockAssert.assertLockedSuspend(lockName)
                    secondStarted.complete(Unit)
                    release.await()
                    "second"
                }
            }

            firstStarted.await()
            secondStarted.await()
            holder.activeCount(lockName) shouldBeEqualTo 2
            contender.runIfLeader(lockName) { "third" }.shouldBeNull()

            release.complete(Unit)
            setOf(first.await(), second.await()) shouldBeEqualTo setOf("first", "second")
        }
    }

    @Test
    fun `cancellation releases group slot for a later attempt`() = runSuspendIO {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdSuspendLeaderGroupElector(client, options)
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
    }

    @Test
    fun `LeaderSlot result carries audit leader id`() = runSuspendIO {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdSuspendLeaderGroupElector(client, options)
            val slot = LeaderSlot(randomName(), "suspend-group-node-a")

            val result = elector.runIfLeaderResultSuspend(slot) {
                "done"
            }

            result.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
            result.value shouldBeEqualTo "done"
            result.leaderId shouldBeEqualTo "suspend-group-node-a"
        }
    }

    @Test
    fun `extendActiveLockSuspend works inside group body`() = runSuspendIO {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdSuspendLeaderGroupElector(client, options)

            val extended = elector.runIfLeader(randomName()) {
                LockExtender.extendActiveLockSuspend(10.seconds)
            }

            extended shouldBeEqualTo true
        }
    }
}
