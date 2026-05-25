package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbLeaderGroupElectorIntegrationTest : AbstractDynamoDbLeaderTest() {

    @Test
    fun `runIfLeader never exceeds max leaders under contention`() {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val lockName = randomName()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val elected = AtomicInteger(0)

        MultithreadingTester()
            .workers(8)
            .rounds(2)
            .add {
                elector.runIfLeader(lockName) {
                    LockAssert.assertLocked(lockName)
                    val current = active.incrementAndGet()
                    peak.updateAndGet { max(it, current) }
                    Thread.sleep(25)
                    elected.incrementAndGet()
                    active.decrementAndGet()
                }
            }
            .run()

        peak.get() shouldBeLessOrEqualTo 2
        elected.get() shouldBeGreaterOrEqualTo 2
        elector.activeCount(lockName) shouldBeEqualTo 0
        elector.availableSlots(lockName) shouldBeEqualTo 2
    }

    @Test
    fun `released slot can be reacquired and state is updated`() {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockExtender.extendActiveLock(5.seconds)
            elector.state(lockName).activeCount shouldBeEqualTo 1
            "first"
        } shouldBeEqualTo "first"

        elector.state(lockName).activeCount shouldBeEqualTo 0
        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `group state snapshot reports empty occupied slot and does not release holder`() {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 1.seconds,
                leaseTime = 10.seconds,
                nodeId = "dynamodb-group-state-node-a",
            ),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 150.milliseconds, leaseTime = 10.seconds),
        )
        val slot = LeaderSlot(lockName = randomName(), leaderId = "dynamodb-group-state-audit-node-a")
        val empty = holder.state(slot.lockName)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        empty.lockName shouldBeEqualTo slot.lockName
        empty.maxLeaders shouldBeEqualTo 1
        empty.activeCount shouldBeEqualTo 0
        empty.availableSlots shouldBeEqualTo 1
        empty.leaders shouldBeEqualTo emptyList()

        try {
            val holderFuture = executor.submit<String?> {
                holder.runIfLeader(slot) {
                    val state = holder.state(slot.lockName)
                    val lease = state.leaders.single()

                    state.lockName shouldBeEqualTo slot.lockName
                    state.maxLeaders shouldBeEqualTo 1
                    state.activeCount shouldBeEqualTo 1
                    state.availableSlots shouldBeEqualTo 0
                    lease.auditLeaderId shouldBeEqualTo "dynamodb-group-state-audit-node-a"
                    lease.nodeId shouldBeEqualTo "dynamodb-group-state-node-a"
                    lease.slot shouldBeEqualTo 0
                    lease.leaseUntil.shouldNotBeNull()
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    "holder"
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
            contender.runIfLeader(slot.lockName) { "contender" }.shouldBeNull()
            holder.state(slot.lockName).activeCount shouldBeEqualTo 1

            release.countDown()
            holderFuture.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }

        holder.state(slot.lockName).activeCount shouldBeEqualTo 0
        contender.runIfLeader(slot.lockName) { "takeover" } shouldBeEqualTo "takeover"
    }

    @Test
    fun `slot leader id is stored as group audit identity`() {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 2,
                waitTime = 1.seconds,
                leaseTime = 5.seconds,
                nodeId = "dynamodb-group-node-a",
            ),
        )
        val slot = LeaderSlot(randomName(), "dynamodb-group-audit-node-a")

        val result = elector.runIfLeaderResult(slot) {
            val lease = elector.state(slot.lockName).leaders.single()
            lease.auditLeaderId shouldBeEqualTo "dynamodb-group-audit-node-a"
            lease.nodeId shouldBeEqualTo "dynamodb-group-node-a"
            "ok"
        }

        result shouldBeEqualTo LeaderRunResult.Elected("ok", leaderId = "dynamodb-group-audit-node-a")
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        groupOptions: LeaderGroupElectionOptions =
            LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 5.seconds),
    ): DynamoDbLeaderGroupElector =
        DynamoDbLeaderGroupElector(
            dynamoDb,
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = groupOptions,
                tableName = tableName,
                keyPrefix = keyPrefix,
                clockSkewTolerance = 10.milliseconds,
                ttlPadding = 1.seconds,
            ),
        )
}
