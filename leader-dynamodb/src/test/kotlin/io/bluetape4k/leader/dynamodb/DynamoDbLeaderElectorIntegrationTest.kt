package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
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
    fun `runIfLeader keeps only one concurrent leader under contention`() {
        val elector = newElector(
            leaderOptions = LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
        )
        val lockName = randomName()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val elected = AtomicInteger(0)

        MultithreadingTester()
            .workers(6)
            .rounds(2)
            .add {
                elector.runIfLeader(lockName) {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { max(it, current) }
                    Thread.sleep(25)
                    elected.incrementAndGet()
                    active.decrementAndGet()
                }
            }
            .run()

        peak.get() shouldBeLessOrEqualTo 1
        elected.get() shouldBeGreaterOrEqualTo 1
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
    fun `watchdog keeps lease active beyond lease time`() {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 600.milliseconds,
                autoExtend = true,
            ),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 600.milliseconds),
        )
        val lockName = randomName()

        holder.runIfLeader(lockName) {
            Thread.sleep(1_300)
            contender.runIfLeader(lockName) { "contender" }.shouldBeNull()
            "holder"
        } shouldBeEqualTo "holder"

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

    @Test
    fun `runAsyncIfLeader acquires and releases lock`() {
        val elector = newElector()
        val lockName = randomName()

        val result = elector.runAsyncIfLeader(lockName, ForkJoinPool.commonPool()) {
            CompletableFuture.completedFuture("async")
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async"
        elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @Test
    fun `runAsyncIfLeaderResult returns elected result with audit identity`() {
        val elector = newElector()
        val slot = LeaderSlot(randomName(), "dynamodb-async-audit")

        val result = elector.runAsyncIfLeaderResult(slot, ForkJoinPool.commonPool()) {
            CompletableFuture.completedFuture("async-result")
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo LeaderRunResult.Elected("async-result", leaderId = "dynamodb-async-audit")
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
