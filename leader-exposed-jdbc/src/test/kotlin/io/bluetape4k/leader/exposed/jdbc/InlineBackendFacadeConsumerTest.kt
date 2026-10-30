package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until

class InlineBackendFacadeConsumerTest : AbstractExposedJdbcLeaderTest() {

    private fun callRunIfLeaderExplicit(db: Database, lockName: String, action: () -> String): String? =
        db.runIfLeader(lockName, ExposedJdbcLeaderElectionOptions.Default, action)

    private fun callRunIfLeaderDefault(db: Database, lockName: String, action: () -> String): String? =
        db.runIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderExplicit(db: Database, lockName: String, action: () -> CompletableFuture<String>) =
        db.runAsyncIfLeader(lockName, VirtualThreadExecutor, ExposedJdbcLeaderElectionOptions.Default, action)

    private fun callRunAsyncIfLeaderDefault(db: Database, lockName: String, action: () -> CompletableFuture<String>) =
        db.runAsyncIfLeader(lockName, action = action)

    private fun callRunVirtualIfLeaderExplicit(db: Database, lockName: String, action: () -> String) =
        db.runVirtualIfLeader(lockName, ExposedJdbcLeaderElectionOptions.Default, action)

    private fun callRunVirtualIfLeaderDefault(db: Database, lockName: String, action: () -> String) =
        db.runVirtualIfLeader(lockName, action = action)

    private fun callRunIfLeaderGroupExplicit(db: Database, lockName: String, action: () -> String): String? =
        db.runIfLeaderGroup(lockName, ExposedJdbcLeaderGroupElectionOptions.Default, action)

    private fun callRunIfLeaderGroupDefault(db: Database, lockName: String, action: () -> String): String? =
        db.runIfLeaderGroup(lockName, action = action)

    private fun callRunAsyncIfLeaderGroupExplicit(db: Database, lockName: String, action: () -> CompletableFuture<String>) =
        db.runAsyncIfLeaderGroup(lockName, VirtualThreadExecutor, ExposedJdbcLeaderGroupElectionOptions.Default, action)

    private fun callRunAsyncIfLeaderGroupDefault(db: Database, lockName: String, action: () -> CompletableFuture<String>) =
        db.runAsyncIfLeaderGroup(lockName, action = action)

    @Test
    fun `source consumers call every jdbc facade and delegate each callback once`() {
        val db = connectDb(TestDB.H2)
        val calls = AtomicInteger()
        val next: () -> String = { "call-${calls.incrementAndGet()}" }
        val nextAsync: () -> CompletableFuture<String> = { CompletableFuture.completedFuture(next()) }

        callRunIfLeaderExplicit(db, randomName(), next) shouldBeEqualTo "call-1"
        callRunAsyncIfLeaderExplicit(db, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-2"
        callRunVirtualIfLeaderExplicit(db, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-3"
        callRunIfLeaderGroupExplicit(db, randomName(), next) shouldBeEqualTo "call-4"
        callRunAsyncIfLeaderGroupExplicit(db, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-5"

        callRunIfLeaderDefault(db, randomName(), next) shouldBeEqualTo "call-6"
        callRunAsyncIfLeaderDefault(db, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-7"
        callRunVirtualIfLeaderDefault(db, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-8"
        callRunIfLeaderGroupDefault(db, randomName(), next) shouldBeEqualTo "call-9"
        callRunAsyncIfLeaderGroupDefault(db, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-10"

        calls.get() shouldBeEqualTo 10
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `facade contention skips single and group callbacks`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val singleName = randomName()
        val singleHolder = ExposedJdbcLock(db, singleName, RetryStrategy.Jitter())
        singleHolder.tryLock(1.seconds, 30.seconds).shouldBeTrue()
        try {
            val singleCalls = AtomicInteger()
            val singleOptions = ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds),
            )
            db.runIfLeader(singleName, singleOptions) {
                singleCalls.incrementAndGet()
                "must-not-run"
            }.shouldBeNull()
            singleCalls.get() shouldBeEqualTo 0
        } finally {
            singleHolder.unlock()
        }

        val groupName = randomName()
        val groupHolder = ExposedJdbcGroupLock(
            db,
            groupName,
            slot = 0,
            RetryStrategy.Jitter(),
        )
        groupHolder.tryLock(1.seconds, 30.seconds).shouldBeTrue()
        try {
            val groupCalls = AtomicInteger()
            val groupOptions = ExposedJdbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 1,
                    waitTime = 100.milliseconds,
                    leaseTime = 5.seconds,
                ),
            )
            db.runIfLeaderGroup(groupName, groupOptions) {
                groupCalls.incrementAndGet()
                "must-not-run"
            }.shouldBeNull()
            groupCalls.get() shouldBeEqualTo 0
        } finally {
            groupHolder.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `sync facade callback failure releases the lock`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        assertFailsWith<LeaderElectionException> {
            db.runIfLeader(lockName) {
                throw LeaderElectionException("issue-994 facade failure")
            }
        }

        db.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `group facade failure and async cancellation release the slot`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
        )

        assertFailsWith<LeaderGroupElectionException> {
            db.runIfLeaderGroup(lockName, options) {
                throw LeaderGroupElectionException("issue-994 group facade failure")
            }
        }
        db.runIfLeaderGroup(lockName, options) { "after-failure" } shouldBeEqualTo "after-failure"

        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        val result = db.runAsyncIfLeaderGroup(lockName, VirtualThreadExecutor, options) {
            actionStarted.countDown()
            action
        }
        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        await atMost 5.seconds until { action.isCancelled }
        db.runIfLeaderGroup(lockName, options) { "after-cancel" } shouldBeEqualTo "after-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `async facade cancellation propagates and permits reacquisition`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        val result = db.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            actionStarted.countDown()
            action
        }

        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        await atMost 5.seconds until { action.isCancelled }

        db.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @Test
    fun `virtual facade cancellation interrupts callback and permits reacquisition`() {
        val db = connectDb(TestDB.H2)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val actionStarted = CountDownLatch(1)
        val actionInterrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = db.runVirtualIfLeader(lockName, options) {
            actionStarted.countDown()
            try {
                release.await()
                "should-not-complete"
            } catch (error: InterruptedException) {
                actionInterrupted.countDown()
                throw error
            }
        }

        try {
            actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(true).shouldBeTrue()
            actionInterrupted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            await atMost 5.seconds until {
                db.runIfLeader(lockName, options) { "reacquired" } == "reacquired"
            }
        } finally {
            release.countDown()
        }
    }
}
