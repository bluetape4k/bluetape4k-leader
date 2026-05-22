package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EtcdLeaderGroupElectorIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `runIfLeader allows max leaders and skips next contender`() {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val holder = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 5.seconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val contender = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 250.milliseconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val lockName = randomName()
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val first = executor.submit<String?> {
                    holder.runIfLeader(lockName) {
                        LockAssert.assertLocked(lockName)
                        started.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        "first"
                    }
                }
                val second = executor.submit<String?> {
                    holder.runIfLeader(lockName) {
                        LockAssert.assertLocked(lockName)
                        started.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        "second"
                    }
                }

                started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
                holder.activeCount(lockName) shouldBeEqualTo 2
                holder.availableSlots(lockName) shouldBeEqualTo 0
                contender.runIfLeader(lockName) { "third" }.shouldBeNull()

                release.countDown()
                setOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)) shouldBeEqualTo
                    setOf("first", "second")
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `released slot can be reacquired and state is updated`() {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderGroupElector(client, options)
            val lockName = randomName()

            elector.runIfLeader(lockName) {
                elector.state(lockName).activeCount shouldBeEqualTo 1
                "first"
            } shouldBeEqualTo "first"

            elector.state(lockName).activeCount shouldBeEqualTo 0
            elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
        }
    }

    @Test
    fun `activeCount ignores queued contenders for occupied slots`() {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderGroupElector(client, options)
            val lockName = randomName()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()

            try {
                val holder = executor.submit<String?> {
                    elector.runIfLeader(lockName) {
                        started.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        "holder"
                    }
                }

                started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true

                val contender = Thread {
                    elector.runIfLeader(lockName) { "contender" }
                }.apply { start() }

                Thread.sleep(250)
                elector.activeCount(lockName) shouldBeEqualTo 1
                elector.state(lockName).activeCount shouldBeEqualTo 1

                release.countDown()
                holder.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
                contender.join(5_000)
                contender.isAlive shouldBeEqualTo false
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `LeaderSlot result carries audit leader id`() {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderGroupElector(client, options)
            val slot = LeaderSlot(randomName(), "group-node-a")

            val result = elector.runIfLeaderResult(slot) {
                "done"
            }

            result.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
            result.value shouldBeEqualTo "done"
            result.leaderId shouldBeEqualTo "group-node-a"
        }
    }

    @Test
    fun `extendActiveLock works inside group body`() {
        newClient().use { client ->
            val options = EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 2.seconds, leaseTime = 10.seconds),
                keyPrefix = "/bluetape4k/leader/test/${randomName()}",
            )
            val elector = EtcdLeaderGroupElector(client, options)

            val extended = elector.runIfLeader(randomName()) {
                LockExtender.extendActiveLock(10.seconds)
            }

            extended shouldBeEqualTo true
        }
    }
}
