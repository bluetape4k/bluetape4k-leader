package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
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

class DynamoDbLeaderGroupElectorIntegrationTest : AbstractDynamoDbLeaderTest() {

    @Test
    fun `runIfLeader allows max leaders and skips next contender`() {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 150.milliseconds, leaseTime = 5.seconds),
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
                    release.await(5, TimeUnit.SECONDS)
                    "first"
                }
            }
            val second = executor.submit<String?> {
                holder.runIfLeader(lockName) {
                    LockAssert.assertLocked(lockName)
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "second"
                }
            }

            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            holder.activeCount(lockName) shouldBeEqualTo 2
            holder.availableSlots(lockName) shouldBeEqualTo 0
            contender.runIfLeader(lockName) { "third" }.shouldBeNull()

            release.countDown()
            setOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)) shouldBeEqualTo setOf("first", "second")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
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
