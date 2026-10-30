package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.lock.MongoLock
import org.bson.Document
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InlineBackendFacadeConsumerTest : AbstractMongoLeaderTest() {

    private fun callRunIfLeaderExplicit(collection: MongoCollection<Document>, lockName: String, action: () -> String): String? =
        collection.runIfLeader(lockName, MongoLeaderElectionOptions.Default, action)

    private fun callRunIfLeaderDefault(collection: MongoCollection<Document>, lockName: String, action: () -> String): String? =
        collection.runIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderExplicit(collection: MongoCollection<Document>, lockName: String, action: () -> CompletableFuture<String>) =
        collection.runAsyncIfLeader(lockName, VirtualThreadExecutor, MongoLeaderElectionOptions.Default, action)

    private fun callRunAsyncIfLeaderDefault(collection: MongoCollection<Document>, lockName: String, action: () -> CompletableFuture<String>) =
        collection.runAsyncIfLeader(lockName, action = action)

    private fun callRunIfLeaderGroupExplicit(collection: MongoCollection<Document>, lockName: String, action: () -> String): String? =
        collection.runIfLeaderGroup(lockName, MongoLeaderGroupElectionOptions.Default, action)

    private fun callRunIfLeaderGroupDefault(collection: MongoCollection<Document>, lockName: String, action: () -> String): String? =
        collection.runIfLeaderGroup(lockName, action = action)

    private fun callRunAsyncIfLeaderGroupExplicit(collection: MongoCollection<Document>, lockName: String, action: () -> CompletableFuture<String>) =
        collection.runAsyncIfLeaderGroup(lockName, VirtualThreadExecutor, MongoLeaderGroupElectionOptions.Default, action)

    private fun callRunAsyncIfLeaderGroupDefault(collection: MongoCollection<Document>, lockName: String, action: () -> CompletableFuture<String>) =
        collection.runAsyncIfLeaderGroup(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderExplicit(collection: CoroutineMongoCollection<Document>, lockName: String, action: suspend () -> String) =
        collection.suspendRunIfLeader(lockName, MongoLeaderElectionOptions.Default, action)

    private suspend fun callSuspendRunIfLeaderDefault(collection: CoroutineMongoCollection<Document>, lockName: String, action: suspend () -> String) =
        collection.suspendRunIfLeader(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderGroupExplicit(
        collection: MongoCollection<Document>,
        coroutineGroupCollection: CoroutineMongoCollection<Document>,
        lockName: String,
        action: suspend () -> String,
    ) = collection.suspendRunIfLeaderGroup(coroutineGroupCollection, lockName, MongoLeaderGroupElectionOptions.Default, action)

    private suspend fun callSuspendRunIfLeaderGroupDefault(
        collection: MongoCollection<Document>,
        coroutineGroupCollection: CoroutineMongoCollection<Document>,
        lockName: String,
        action: suspend () -> String,
    ) = collection.suspendRunIfLeaderGroup(coroutineGroupCollection, lockName, action = action)

    @Test
    fun `source consumers call every mongodb facade and delegate each callback once`() = runSuspendIO {
        val calls = AtomicInteger()
        val next: () -> String = { "call-${calls.incrementAndGet()}" }
        val nextAsync: () -> CompletableFuture<String> = { CompletableFuture.completedFuture(next()) }

        callRunIfLeaderExplicit(lockCollection, randomName(), next) shouldBeEqualTo "call-1"
        callRunAsyncIfLeaderExplicit(lockCollection, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-2"
        callRunIfLeaderGroupExplicit(groupLockCollection, randomName(), next) shouldBeEqualTo "call-3"
        callRunAsyncIfLeaderGroupExplicit(groupLockCollection, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-4"
        callSuspendRunIfLeaderExplicit(coroutineLockCollection, randomName()) { next() } shouldBeEqualTo "call-5"
        callSuspendRunIfLeaderGroupExplicit(
            groupLockCollection,
            coroutineGroupLockCollection,
            randomName(),
        ) { next() } shouldBeEqualTo "call-6"

        callRunIfLeaderDefault(lockCollection, randomName(), next) shouldBeEqualTo "call-7"
        callRunAsyncIfLeaderDefault(lockCollection, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-8"
        callRunIfLeaderGroupDefault(groupLockCollection, randomName(), next) shouldBeEqualTo "call-9"
        callRunAsyncIfLeaderGroupDefault(groupLockCollection, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-10"
        callSuspendRunIfLeaderDefault(coroutineLockCollection, randomName()) { next() } shouldBeEqualTo "call-11"
        callSuspendRunIfLeaderGroupDefault(
            groupLockCollection,
            coroutineGroupLockCollection,
            randomName(),
        ) { next() } shouldBeEqualTo "call-12"

        calls.get() shouldBeEqualTo 12
    }

    @Test
    fun `facades skip callbacks during single and group contention`() {
        val singleName = randomName()
        val singleHolder = MongoLock(lockCollection, singleName)
        singleHolder.tryLock(1.seconds, 30.seconds).shouldBeTrue()
        try {
            val calls = AtomicInteger()
            val options = MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds),
            )
            lockCollection.runIfLeader(singleName, options) {
                calls.incrementAndGet()
                "must-not-run"
            }.shouldBeNull()
            calls.get() shouldBeEqualTo 0
        } finally {
            singleHolder.unlock()
        }

        val groupName = randomName()
        val groupHolder = MongoLock(groupLockCollection, "$groupName:slot:0")
        groupHolder.tryLock(1.seconds, 30.seconds).shouldBeTrue()
        try {
            val calls = AtomicInteger()
            val options = MongoLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 1,
                    waitTime = 100.milliseconds,
                    leaseTime = 5.seconds,
                ),
            )
            groupLockCollection.runIfLeaderGroup(groupName, options) {
                calls.incrementAndGet()
                "must-not-run"
            }.shouldBeNull()
            calls.get() shouldBeEqualTo 0
        } finally {
            groupHolder.unlock()
        }
    }

    @Test
    fun `sync callback failure releases the lock and async cancellation permits reacquisition`() {
        val lockName = randomName()
        val options = MongoLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val failure = LeaderElectionException("issue-994 mongo failure")

        assertFailsWith<LeaderElectionException> {
            lockCollection.runIfLeader(lockName, options) { throw failure }
        }
        lockCollection.runIfLeader(lockName, options) { "after-failure" } shouldBeEqualTo "after-failure"

        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        val result = lockCollection.runAsyncIfLeader(lockName, VirtualThreadExecutor, options) {
            actionStarted.countDown()
            action
        }
        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        await atMost 5.seconds until { action.isCancelled }
        await atMost 5.seconds until {
            lockCollection.countDocuments(Filters.eq("_id", lockName)) == 0L
        }
        lockCollection.runIfLeader(lockName, options) { "after-cancel" } shouldBeEqualTo "after-cancel"
        action.cancel(true)
    }

    @Test
    fun `group facade failure and async cancellation release the slot`() {
        val lockName = randomName()
        val options = MongoLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val failure = IllegalStateException("issue-994 mongo group failure")

        assertFailsWith<IllegalStateException> {
            groupLockCollection.runIfLeaderGroup(lockName, options) {
                throw failure
            }
        }
        groupLockCollection.runIfLeaderGroup(lockName, options) { "after-failure" } shouldBeEqualTo "after-failure"

        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        val result = groupLockCollection.runAsyncIfLeaderGroup(lockName, VirtualThreadExecutor, options) {
            actionStarted.countDown()
            action
        }
        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        await atMost 5.seconds until { action.isCancelled }
        await atMost 5.seconds until {
            groupLockCollection.countDocuments(Filters.eq("_id", "$lockName:slot:0")) == 0L
        }
        groupLockCollection.runIfLeaderGroup(lockName, options) { "after-cancel" } shouldBeEqualTo "after-cancel"
        action.cancel(true)
    }

    @Test
    fun `suspend facade cancellation releases the lock`() = runSuspendIO {
        val lockName = randomName()
        val options = MongoLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val acquired = CompletableDeferred<Unit>()
        val job = launch {
            coroutineLockCollection.suspendRunIfLeader(lockName, options) {
                acquired.complete(Unit)
                delay(Long.MAX_VALUE.milliseconds)
            }
        }

        acquired.await()
        job.cancelAndJoin()
        await atMost 5.seconds until {
            lockCollection.countDocuments(Filters.eq("_id", lockName)) == 0L
        }
        coroutineLockCollection.suspendRunIfLeader(lockName, options) { "reacquired" } shouldBeEqualTo "reacquired"
    }
}
