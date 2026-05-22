package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EtcdSuspendLeaderElectorIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() = runSuspendIO {
        newClient().use { client ->
            val options = EtcdLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdSuspendLeaderElector(client, options)
            val lockName = randomName()

            elector.runIfLeader(lockName) {
                LockAssert.assertLockedSuspend(lockName)
                "first"
            } shouldBeEqualTo "first"

            elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
        }
    }

    @Test
    fun `runIfLeader returns null on contention`() = runSuspendIO {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val holder = EtcdSuspendLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val contender = EtcdSuspendLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
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
    }

    @Test
    fun `cancellation releases the lock for a later attempt`() = runSuspendIO {
        newClient().use { client ->
            val options = EtcdLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdSuspendLeaderElector(client, options)
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
    fun `extendActiveLockSuspend works inside elected body`() = runSuspendIO {
        newClient().use { client ->
            val options = EtcdLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdSuspendLeaderElector(client, options)

            val extended = elector.runIfLeader(randomName()) {
                LockExtender.extendActiveLockSuspend(10.seconds)
            }

            extended shouldBeEqualTo true
        }
    }
}
