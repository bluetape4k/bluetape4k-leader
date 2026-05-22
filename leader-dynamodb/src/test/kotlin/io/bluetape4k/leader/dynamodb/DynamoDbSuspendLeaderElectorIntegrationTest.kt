package io.bluetape4k.leader.dynamodb

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

class DynamoDbSuspendLeaderElectorIntegrationTest : AbstractDynamoDbLeaderTest() {

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() = runSuspendIO {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLockedSuspend(lockName)
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `runIfLeader returns null on contention`() = runSuspendIO {
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

    @Test
    fun `cancellation releases lease for next suspend attempt`() = runSuspendIO {
        val elector = newElector()
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

    @Test
    fun `extendActiveLockSuspend works inside elected body`() = runSuspendIO {
        val elector = newElector()

        val extended = elector.runIfLeader(randomName()) {
            LockExtender.extendActiveLockSuspend(5.seconds)
        }

        extended shouldBeEqualTo true
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
    ): DynamoDbSuspendLeaderElector =
        DynamoDbSuspendLeaderElector(
            dynamoDbAsync,
            DynamoDbLeaderElectionOptions(
                leaderOptions = leaderOptions,
                tableName = tableName,
                keyPrefix = keyPrefix,
                clockSkewTolerance = 10.milliseconds,
                ttlPadding = 1.seconds,
            ),
        )
}
