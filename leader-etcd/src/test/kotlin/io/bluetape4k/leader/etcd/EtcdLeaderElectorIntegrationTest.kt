package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EtcdLeaderElectorIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() {
        newClient().use { client ->
            val options = EtcdLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderElector(client, options)
            val lockName = randomName()

            elector.runIfLeader(lockName) {
                LockAssert.assertLocked(lockName)
                "first"
            } shouldBeEqualTo "first"

            elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
        }
    }

    @Test
    fun `state snapshot remains empty because etcd single lock metadata is not exposed`() {
        newClient().use { client ->
            val options = EtcdLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderElector(client, options)
            val lockName = randomName()

            elector.state(lockName).isEmpty shouldBeEqualTo true

            elector.runIfLeader(lockName) {
                LockAssert.assertLocked(lockName)
                elector.state(lockName).isEmpty shouldBeEqualTo true
                "holder"
            } shouldBeEqualTo "holder"
        }
    }

    @Test
    fun `runIfLeader returns null on contention`() {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val holder = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val contender = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val lockName = randomName()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()

            try {
                val holderFuture = executor.submit<String?> {
                    holder.runIfLeader(lockName) {
                        started.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        "holder"
                    }
                }

                started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
                contender.runIfLeader(lockName) { "contender" }.shouldBeNull()

                release.countDown()
                holderFuture.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `extendActiveLock works inside elected body`() {
        newClient().use { client ->
            val options = EtcdLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderElector(client, options)

            val extended = elector.runIfLeader(randomName()) {
                LockExtender.extendActiveLock(10.seconds)
            }

            extended shouldBeEqualTo true
        }
    }

    @Test
    fun `Client extension runs elected body`() {
        newClient().use { client ->
            val result = client.runIfLeader(
                lockName = randomName(),
                options = EtcdLeaderElectionOptions(
                    keyPrefix = "/bluetape4k/leader/test/${randomName()}",
                ),
            ) {
                "extension"
            }

            result.shouldNotBeNull()
            result shouldBeEqualTo "extension"
        }
    }
}
