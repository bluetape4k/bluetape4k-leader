package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockAssert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DynamoDbVirtualThreadLeaderElectorIntegrationTest : AbstractDynamoDbLeaderTest() {

    @Test
    fun `single virtual-thread elector acquires and releases`() {
        val elector = DynamoDbVirtualThreadLeaderElector(newSingleElector())
        val lockName = randomName()

        elector.runAsyncIfLeader(lockName) {
            LockAssert.assertLocked(lockName)
            "virtual"
        }.await() shouldBeEqualTo "virtual"

        elector.runAsyncIfLeader(lockName) { "reacquired" }.await() shouldBeEqualTo "reacquired"
    }

    @Test
    fun `single virtual-thread elector returns null on contention`() {
        val keyPrefix = keyPrefix()
        val holder = DynamoDbVirtualThreadLeaderElector(
            newSingleElector(
                keyPrefix = keyPrefix,
                leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
            ),
        )
        val contender = DynamoDbVirtualThreadLeaderElector(
            newSingleElector(
                keyPrefix = keyPrefix,
                leaderOptions = LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
            ),
        )
        val lockName = randomName()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val holderFuture = executor.submit<String?> {
                holder.runAsyncIfLeader(lockName) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "holder"
                }.await()
            }

            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            contender.runAsyncIfLeader(lockName) { "contender" }.await().shouldBeNull()

            release.countDown()
            holderFuture.get(5, TimeUnit.SECONDS) shouldBeEqualTo "holder"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `group virtual-thread elector respects max leaders`() {
        val keyPrefix = keyPrefix()
        val holder = DynamoDbVirtualThreadLeaderGroupElector(
            newGroupElector(
                keyPrefix = keyPrefix,
                groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 1.seconds, leaseTime = 5.seconds),
            ),
        )
        val contender = DynamoDbVirtualThreadLeaderGroupElector(
            newGroupElector(
                keyPrefix = keyPrefix,
                groupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 2,
                    waitTime = 150.milliseconds,
                    leaseTime = 5.seconds,
                ),
            ),
        )
        val lockName = randomName()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<String?> {
                holder.runAsyncIfLeader(lockName) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "first"
                }.await()
            }
            val second = executor.submit<String?> {
                holder.runAsyncIfLeader(lockName) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "second"
                }.await()
            }

            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            holder.activeCount(lockName) shouldBeEqualTo 2
            contender.runAsyncIfLeader(lockName) { "third" }.await().shouldBeNull()

            release.countDown()
            setOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)) shouldBeEqualTo setOf("first", "second")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun newSingleElector(
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

    private fun newGroupElector(
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
