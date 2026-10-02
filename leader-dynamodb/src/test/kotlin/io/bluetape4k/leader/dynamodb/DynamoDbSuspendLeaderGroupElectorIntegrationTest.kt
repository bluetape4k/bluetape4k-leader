package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbSuspendLeaderGroupElectorIntegrationTest: AbstractDynamoDbLeaderTest() {

    companion object: KLoggingChannel()

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend(lockName)
            LockExtender.extendActiveLockSuspend(5.seconds).shouldBeTrue()
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
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 2,
                waitTime = 150.milliseconds,
                leaseTime = 5.seconds
            ),
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
        }.log("Holder A")

        val holderB = async {
            holder.runIfLeader(lockName) {
                startedB.complete(Unit)
                release.await()
                "holder-b"
            }
        }.log("Holder B")

        startedA.await()
        startedB.await()
        contender.runIfLeader(lockName) { "contender" }.shouldBeNull()

        log.debug { "holder state=${holder.state(lockName)}" }
        holder.state(lockName).activeCount shouldBeEqualTo 2

        release.complete(Unit)
        setOf(holderA.await(), holderB.await()) shouldBeEqualTo setOf("holder-a", "holder-b")
    }

    @Test
    fun `cancellation releases group slot for next suspend attempt`() = runSuspendIO {
        val elector = newElector(
            groupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 100.milliseconds,
                leaseTime = 5.seconds
            ),
        )
        val lockName = randomName()

        val started = CompletableDeferred<Unit>()
        val holder = async {
            elector.runIfLeader(lockName) {
                started.complete(Unit)
                delay(10.seconds)
            }
        }.log("Holder A")
        
        started.await()
        holder.cancelAndJoin()

        elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"

        log.debug { "elector.availableSlots=${elector.availableSlots(lockName)}" }
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
        log.debug { "slot=$slot" }

        val result = elector.runIfLeaderResultSuspend(slot) {
            val lease = elector.state(slot.lockName).leaders.single()
            log.debug { "lease=$lease" }
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
