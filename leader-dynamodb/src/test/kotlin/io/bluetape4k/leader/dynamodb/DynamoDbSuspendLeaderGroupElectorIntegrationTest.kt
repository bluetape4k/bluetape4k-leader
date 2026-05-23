package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbSuspendLeaderGroupElectorIntegrationTest : AbstractDynamoDbLeaderTest() {

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend(lockName)
            LockExtender.extendActiveLockSuspend(5.seconds) shouldBeEqualTo true
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `group returns null when all slots are occupied`() = runSuspendIO {
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
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holderA = async {
            holder.runIfLeader(lockName) {
                startedA.complete(Unit)
                release.await()
                "holder-a"
            }
        }
        val holderB = async {
            holder.runIfLeader(lockName) {
                startedB.complete(Unit)
                release.await()
                "holder-b"
            }
        }

        startedA.await()
        startedB.await()
        contender.runIfLeader(lockName) { "contender" }.shouldBeNull()
        holder.state(lockName).activeCount shouldBeEqualTo 2

        release.complete(Unit)
        setOf(holderA.await(), holderB.await()) shouldBeEqualTo setOf("holder-a", "holder-b")
    }

    @Test
    fun `cancellation releases group slot for next suspend attempt`() = runSuspendIO {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds, leaseTime = 5.seconds),
        )
        val lockName = randomName()

        val started = CompletableDeferred<Unit>()
        val holder = async {
            elector.runIfLeader(lockName) {
                started.complete(Unit)
                delay(10.seconds)
            }
        }
        started.await()
        holder.cancelAndJoin()

        elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
        elector.availableSlots(lockName) shouldBeEqualTo 1
    }

    @Test
    fun `slot leader id is stored as suspend group audit identity`() = runSuspendIO {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 2,
                waitTime = 1.seconds,
                leaseTime = 5.seconds,
                nodeId = "dynamodb-suspend-group-node-a",
            ),
        )
        val slot = LeaderSlot(randomName(), "dynamodb-suspend-group-audit-node-a")

        val result = elector.runIfLeaderResultSuspend(slot) {
            val lease = elector.state(slot.lockName).leaders.single()
            lease.auditLeaderId shouldBeEqualTo "dynamodb-suspend-group-audit-node-a"
            lease.nodeId shouldBeEqualTo "dynamodb-suspend-group-node-a"
            "ok"
        }

        result shouldBeEqualTo LeaderRunResult.Elected("ok", leaderId = "dynamodb-suspend-group-audit-node-a")
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        groupOptions: LeaderGroupElectionOptions =
            LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 5.seconds),
    ): DynamoDbSuspendLeaderGroupElector =
        DynamoDbSuspendLeaderGroupElector(
            dynamoDbAsync,
            DynamoDbLeaderGroupElectionOptions(
                leaderGroupOptions = groupOptions,
                tableName = tableName,
                keyPrefix = keyPrefix,
                clockSkewTolerance = 10.milliseconds,
                ttlPadding = 1.seconds,
            ),
        )
}
