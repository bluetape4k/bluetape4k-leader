package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InlineBackendFacadeConsumerTest : AbstractDynamoDbLeaderTest() {

    @BeforeAll
    fun createDefaultOptionsTable() {
        val defaultTable = DynamoDbLeaderElectionOptions.DefaultTableName
        if (dynamoDb.listTables().tableNames().contains(defaultTable)) {
            return
        }

        dynamoDb.createTable(
            CreateTableRequest.builder()
                .tableName(defaultTable)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("lockName")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                )
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("lockName")
                        .keyType(KeyType.HASH)
                        .build(),
                )
                .build(),
        )
        dynamoDb.waiter().waitUntilTableExists { it.tableName(defaultTable) }
    }

    private fun singleOptions(
        prefix: String = keyPrefix(),
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    ) = DynamoDbLeaderElectionOptions(
        leaderOptions = leaderOptions,
        tableName = tableName,
        keyPrefix = prefix,
        clockSkewTolerance = 10.milliseconds,
        ttlPadding = 1.seconds,
    )

    private fun groupOptions(
        prefix: String = keyPrefix(),
        leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    ) = DynamoDbLeaderGroupElectionOptions(
        leaderGroupOptions = leaderGroupOptions,
        tableName = tableName,
        keyPrefix = prefix,
        clockSkewTolerance = 10.milliseconds,
        ttlPadding = 1.seconds,
    )

    private fun callRunIfLeaderExplicit(lockName: String, action: () -> String): String? =
        dynamoDb.runIfLeader(lockName, singleOptions(), action)

    private fun callRunIfLeaderDefault(lockName: String, action: () -> String): String? =
        dynamoDb.runIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderExplicit(lockName: String, action: () -> CompletableFuture<String>): CompletableFuture<String?> =
        dynamoDb.runAsyncIfLeader(lockName, VirtualThreadExecutor, singleOptions(), action)

    private fun callRunAsyncIfLeaderDefault(lockName: String, action: () -> CompletableFuture<String>): CompletableFuture<String?> =
        dynamoDb.runAsyncIfLeader(lockName, action = action)

    private fun callRunIfLeaderGroupExplicit(lockName: String, action: () -> String): String? =
        dynamoDb.runIfLeaderGroup(lockName, groupOptions(), action)

    private fun callRunIfLeaderGroupDefault(lockName: String, action: () -> String): String? =
        dynamoDb.runIfLeaderGroup(lockName, action = action)

    private fun callRunAsyncIfLeaderGroupExplicit(lockName: String, action: () -> CompletableFuture<String>): CompletableFuture<String?> =
        dynamoDb.runAsyncIfLeaderGroup(lockName, VirtualThreadExecutor, groupOptions(), action)

    private fun callRunAsyncIfLeaderGroupDefault(lockName: String, action: () -> CompletableFuture<String>): CompletableFuture<String?> =
        dynamoDb.runAsyncIfLeaderGroup(lockName, action = action)

    private fun callRunVirtualIfLeaderExplicit(lockName: String, action: () -> String) =
        dynamoDb.runVirtualIfLeader(lockName, singleOptions(), action)

    private fun callRunVirtualIfLeaderDefault(lockName: String, action: () -> String) =
        dynamoDb.runVirtualIfLeader(lockName, action = action)

    private fun callRunVirtualIfLeaderGroupExplicit(lockName: String, action: () -> String) =
        dynamoDb.runVirtualIfLeaderGroup(lockName, groupOptions(), action)

    private fun callRunVirtualIfLeaderGroupDefault(lockName: String, action: () -> String) =
        dynamoDb.runVirtualIfLeaderGroup(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderExplicit(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeader(lockName, singleOptions(), action)

    private suspend fun callSuspendRunIfLeaderDefault(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeader(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderGroupExplicit(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeaderGroup(lockName, groupOptions(), action)

    private suspend fun callSuspendRunIfLeaderGroupDefault(lockName: String, action: suspend () -> String): String? =
        dynamoDbAsync.suspendRunIfLeaderGroup(lockName, action = action)

    @Test
    fun `source consumers call every facade and delegate each callback once`() = runSuspendIO {
        val calls = AtomicInteger()
        val next: () -> String = { "call-${calls.incrementAndGet()}" }
        val nextAsync: () -> CompletableFuture<String> = { CompletableFuture.completedFuture(next()) }

        callRunIfLeaderExplicit(randomName(), next) shouldBeEqualTo "call-1"
        callRunAsyncIfLeaderExplicit(randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-2"
        callRunIfLeaderGroupExplicit(randomName(), next) shouldBeEqualTo "call-3"
        callRunAsyncIfLeaderGroupExplicit(randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-4"
        callRunVirtualIfLeaderExplicit(randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-5"
        callRunVirtualIfLeaderGroupExplicit(randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-6"
        callSuspendRunIfLeaderExplicit(randomName()) { next() } shouldBeEqualTo "call-7"
        callSuspendRunIfLeaderGroupExplicit(randomName()) { next() } shouldBeEqualTo "call-8"

        callRunIfLeaderDefault(randomName(), next) shouldBeEqualTo "call-9"
        callRunAsyncIfLeaderDefault(randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-10"
        callRunIfLeaderGroupDefault(randomName(), next) shouldBeEqualTo "call-11"
        callRunAsyncIfLeaderGroupDefault(randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-12"
        callRunVirtualIfLeaderDefault(randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-13"
        callRunVirtualIfLeaderGroupDefault(randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-14"
        callSuspendRunIfLeaderDefault(randomName()) { next() } shouldBeEqualTo "call-15"
        callSuspendRunIfLeaderGroupDefault(randomName()) { next() } shouldBeEqualTo "call-16"

        calls.get() shouldBeEqualTo 16
    }

    @Test
    fun `facades skip callbacks during single and group contention`() {
        val executor = Executors.newFixedThreadPool(2)
        val singlePrefix = keyPrefix()
        val singleLockName = randomName()
        val singleStarted = CountDownLatch(1)
        val singleRelease = CountDownLatch(1)
        val singleHolder = executor.submit {
            dynamoDb.runIfLeader(
                singleLockName,
                singleOptions(
                    singlePrefix,
                    LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
                ),
            ) {
                singleStarted.countDown()
                singleRelease.await()
                "holder"
            }
        }

        val groupPrefix = keyPrefix()
        val groupLockName = randomName()
        val groupStarted = CountDownLatch(1)
        val groupRelease = CountDownLatch(1)
        val groupHolder = executor.submit {
            dynamoDb.runIfLeaderGroup(
                groupLockName,
                groupOptions(
                    groupPrefix,
                    LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
                ),
            ) {
                groupStarted.countDown()
                groupRelease.await()
                "holder"
            }
        }

        try {
            singleStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            groupStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val singleCalls = AtomicInteger()
            dynamoDb.runIfLeader(
                singleLockName,
                singleOptions(
                    singlePrefix,
                    LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
                ),
            ) {
                singleCalls.incrementAndGet()
                "must-not-run"
            }.shouldBeNull()
            singleCalls.get() shouldBeEqualTo 0

            val groupCalls = AtomicInteger()
            dynamoDb.runIfLeaderGroup(
                groupLockName,
                groupOptions(
                    groupPrefix,
                    LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 150.milliseconds, leaseTime = 5.seconds),
                ),
            ) {
                groupCalls.incrementAndGet()
                "must-not-run"
            }.shouldBeNull()
            groupCalls.get() shouldBeEqualTo 0
        } finally {
            singleRelease.countDown()
            groupRelease.countDown()
            singleHolder.get(5, TimeUnit.SECONDS)
            groupHolder.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun `facade callback failure releases the single lock`() {
        val lockName = randomName()
        val failure = IllegalStateException("issue-994 facade failure")

        assertFailsWith<IllegalStateException> {
            dynamoDb.runIfLeader(lockName, singleOptions()) {
                throw failure
            }
        }

        dynamoDb.runIfLeader(lockName, singleOptions()) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @Test
    fun `group facade failure and async cancellation release the slot`() {
        val lockName = randomName()
        val options = groupOptions(
            keyPrefix(),
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val failure = IllegalStateException("issue-994 group facade failure")

        assertFailsWith<IllegalStateException> {
            dynamoDb.runIfLeaderGroup(lockName, options) {
                throw failure
            }
        }
        dynamoDb.runIfLeaderGroup(lockName, options) { "after-failure" } shouldBeEqualTo "after-failure"

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        try {
            val result = dynamoDb.runAsyncIfLeaderGroup(lockName, executor, options) {
                actionStarted.countDown()
                action
            }

            actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            await atMost 5.seconds until { action.isCancelled }
            dynamoDb.runIfLeaderGroup(lockName, options) { "after-cancel" } shouldBeEqualTo "after-cancel"
        } finally {
            action.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun `async facade cancellation propagates and permits reacquisition`() {
        val lockName = randomName()
        val options = singleOptions(
            keyPrefix(),
            LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()

        try {
            val result = dynamoDb.runAsyncIfLeader(lockName, executor, options) {
                actionStarted.countDown()
                action
            }

            actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            await atMost 5.seconds until { action.isCancelled }
            dynamoDb.runIfLeader(lockName, options) { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            action.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun `suspend facade cancellation releases the lock`() = runSuspendIO {
        val lockName = randomName()
        val options = singleOptions(
            keyPrefix(),
            LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100.milliseconds) {
                dynamoDbAsync.suspendRunIfLeader(lockName, options) {
                    delay(10.seconds)
                }
            }
        }

        dynamoDbAsync.suspendRunIfLeader(lockName, options) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    @Test
    fun `virtual single facade cancellation interrupts callback and permits reacquisition`() {
        val lockName = randomName()
        val options = singleOptions(
            keyPrefix(),
            LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val actionStarted = CountDownLatch(1)
        val actionInterrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = dynamoDb.runVirtualIfLeader(lockName, options) {
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
                dynamoDb.runIfLeader(lockName, options) { "reacquired" } == "reacquired"
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `virtual group facade cancellation interrupts callback and permits slot reuse`() {
        val lockName = randomName()
        val options = groupOptions(
            keyPrefix(),
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val actionStarted = CountDownLatch(1)
        val actionInterrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = dynamoDb.runVirtualIfLeaderGroup(lockName, options) {
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
                dynamoDb.runIfLeaderGroup(lockName, options) { "slot-reused" } == "slot-reused"
            }
        } finally {
            release.countDown()
        }
    }
}
