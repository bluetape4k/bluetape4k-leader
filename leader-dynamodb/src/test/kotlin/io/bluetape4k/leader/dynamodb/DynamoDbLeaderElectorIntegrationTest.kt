package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
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

class DynamoDbLeaderElectorIntegrationTest : AbstractDynamoDbLeaderTest() {

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLocked(lockName)
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `runIfLeader returns null on contention`() {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
        )
        val lockName = randomName()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val holderFuture = executor.submit<String?> {
                holder.runIfLeader(lockName) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "holder"
                }
            }

            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            contender.runIfLeader(lockName) { "contender" }.shouldBeNull()

            release.countDown()
            holderFuture.get(5, TimeUnit.SECONDS) shouldBeEqualTo "holder"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `minLeaseTime retained row blocks until logical lease expires then allows takeover`() {
        val keyPrefix = keyPrefix()
        val lockName = randomName()
        val holder = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 700.milliseconds,
                minLeaseTime = 500.milliseconds,
            ),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 700.milliseconds),
        )

        holder.runIfLeader(lockName) { "holder" } shouldBeEqualTo "holder"
        contender.runIfLeader(lockName) { "too-early" }.shouldBeNull()

        Thread.sleep(650)

        contender.runIfLeader(lockName) { "takeover" } shouldBeEqualTo "takeover"
    }

    @Test
    fun `extendActiveLock works inside elected body`() {
        val elector = newElector()

        val extended = elector.runIfLeader(randomName()) {
            LockExtender.extendActiveLock(5.seconds)
        }

        extended shouldBeEqualTo true
    }

    @Test
    fun `slot leader id is stored as audit identity`() {
        val elector = newElector(
            leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds, nodeId = "dynamodb-node-a"),
        )
        val slot = LeaderSlot(randomName(), "dynamodb-audit-node-a")

        val result = elector.runIfLeaderResult(slot) {
            val lease = elector.state(slot.lockName).leader
            lease?.auditLeaderId shouldBeEqualTo "dynamodb-audit-node-a"
            lease?.nodeId shouldBeEqualTo "dynamodb-node-a"
            "ok"
        }

        result shouldBeEqualTo LeaderRunResult.Elected("ok", leaderId = "dynamodb-audit-node-a")
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
    ): DynamoDbLeaderElector =
        DynamoDbLeaderElector(
            dynamoDb,
            DynamoDbLeaderElectionOptions(
                leaderOptions = leaderOptions,
                tableName = tableName,
                keyPrefix = keyPrefix,
                clockSkewTolerance = 10.milliseconds,
                ttlPadding = 1.seconds,
            ),
        )
}
