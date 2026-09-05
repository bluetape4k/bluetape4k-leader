package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLockClient
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class EtcdAsyncLifecycleTest {

    @Test
    fun `single caller cancellation cancels action and releases lease`() {
        val client = StatefulEtcdLockClient()
        val elector = EtcdLeaderElector.create(client, singleOptions())
        val executor = Executors.newSingleThreadExecutor()
        val action = CancellationRecordingFuture<String>()
        val actionStarted = CountDownLatch(1)

        try {
            val result = elector.runAsyncIfLeader("lock-a", executor) {
                actionStarted.countDown()
                action
            }

            actionStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(true).shouldBeTrue()

            action.cancelled.await(2, TimeUnit.SECONDS).shouldBeTrue()
            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            action.complete("cleanup")
            executor.shutdownNow()
        }
    }

    @Test
    fun `group caller cancellation cancels action and releases slot`() {
        val client = StatefulEtcdLockClient()
        val elector = EtcdLeaderGroupElector.create(client, groupOptions())
        val executor = Executors.newSingleThreadExecutor()
        val action = CancellationRecordingFuture<String>()
        val actionStarted = CountDownLatch(1)

        try {
            val result = elector.runAsyncIfLeader("lock-a", executor) {
                actionStarted.countDown()
                action
            }

            actionStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(true).shouldBeTrue()

            action.cancelled.await(2, TimeUnit.SECONDS).shouldBeTrue()
            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            action.complete("cleanup")
            executor.shutdownNow()
        }
    }

    @Test
    fun `single cancellation before acquisition releases late ownership without invoking action`() {
        val client = StatefulEtcdLockClient(pendFirstLock = true)
        val elector = EtcdLeaderElector.create(client, singleOptions())
        val executor = Executors.newSingleThreadExecutor()
        val actionInvoked = AtomicBoolean()

        try {
            val result = elector.runAsyncIfLeader("lock-a", executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("should-not-run")
            }

            client.pendingAcquisition.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(true).shouldBeTrue()
            client.completePendingOwnership()

            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            actionInvoked.get().shouldBeFalse()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `group cancellation before acquisition releases late ownership without invoking action`() {
        val client = StatefulEtcdLockClient(pendFirstLock = true)
        val elector = EtcdLeaderGroupElector.create(client, groupOptions())
        val executor = Executors.newSingleThreadExecutor()
        val actionInvoked = AtomicBoolean()

        try {
            val result = elector.runAsyncIfLeader("lock-a", executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("should-not-run")
            }

            client.pendingAcquisition.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(true).shouldBeTrue()
            client.completePendingOwnership()

            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            actionInvoked.get().shouldBeFalse()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `single second executor rejection releases acquired lease`() {
        val client = StatefulEtcdLockClient()
        val elector = EtcdLeaderElector.create(client, singleOptions())
        val worker = Executors.newSingleThreadExecutor()
        val actionInvoked = AtomicBoolean()
        val executor = rejectAfterFirstSubmission(worker)

        try {
            val result = elector.runAsyncIfLeader("lock-a", executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("should-not-run")
            }

            val failure = assertFailsWith<CompletionException> { result.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            failure.cause?.message shouldBeEqualTo "rejected-after-acquire"
            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            actionInvoked.get().shouldBeFalse()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `group second executor rejection releases acquired slot`() {
        val client = StatefulEtcdLockClient()
        val elector = EtcdLeaderGroupElector.create(client, groupOptions())
        val worker = Executors.newSingleThreadExecutor()
        val actionInvoked = AtomicBoolean()
        val executor = rejectAfterFirstSubmission(worker)

        try {
            val result = elector.runAsyncIfLeader("lock-a", executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("should-not-run")
            }

            val failure = assertFailsWith<CompletionException> { result.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            failure.cause?.message shouldBeEqualTo "rejected-after-acquire"
            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            actionInvoked.get().shouldBeFalse()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `single action supplier failure preserves cause and releases lease`() {
        val client = StatefulEtcdLockClient()
        val elector = EtcdLeaderElector.create(client, singleOptions())
        val executor = Executors.newSingleThreadExecutor()

        try {
            val failure = assertFailsWith<CompletionException> {
                elector.runAsyncIfLeader<String>("lock-a", executor) {
                    throw IllegalStateException("action-supplier-failed")
                }.join()
            }

            failure.cause.shouldBeInstanceOf<IllegalStateException>()
            failure.cause?.message shouldBeEqualTo "action-supplier-failed"
            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `group action supplier failure preserves cause and releases slot`() {
        val client = StatefulEtcdLockClient()
        val elector = EtcdLeaderGroupElector.create(client, groupOptions())
        val executor = Executors.newSingleThreadExecutor()

        try {
            val failure = assertFailsWith<CompletionException> {
                elector.runAsyncIfLeader<String>("lock-a", executor) {
                    throw IllegalStateException("action-supplier-failed")
                }.join()
            }

            failure.cause.shouldBeInstanceOf<IllegalStateException>()
            failure.cause?.message shouldBeEqualTo "action-supplier-failed"
            client.cleaned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            client.unlockCalls.get() shouldBeEqualTo 1
            client.revokeCalls.get() shouldBeEqualTo 1
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `first executor rejection performs no backend call`() {
        val singleClient = StatefulEtcdLockClient()
        val groupClient = StatefulEtcdLockClient()
        val rejectingExecutor = Executor { throw RejectedExecutionException("rejected-before-acquire") }

        assertFailsWith<RejectedExecutionException> {
            EtcdLeaderElector.create(singleClient, singleOptions())
                .runAsyncIfLeader("lock-a", rejectingExecutor) { CompletableFuture.completedFuture("unused") }
        }
        assertFailsWith<RejectedExecutionException> {
            EtcdLeaderGroupElector.create(groupClient, groupOptions())
                .runAsyncIfLeader("lock-a", rejectingExecutor) { CompletableFuture.completedFuture("unused") }
        }

        singleClient.backendCalls() shouldBeEqualTo 0
        groupClient.backendCalls() shouldBeEqualTo 0
    }

    private fun singleOptions(): EtcdLeaderElectionOptions =
        EtcdLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
        )

    private fun groupOptions(): EtcdLeaderGroupElectionOptions =
        EtcdLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            ),
        )

    private fun rejectAfterFirstSubmission(worker: Executor): Executor {
        val submissions = AtomicInteger()
        return Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("rejected-after-acquire")
            }
        }
    }

    private class CancellationRecordingFuture<T>: CompletableFuture<T>() {
        val cancelled = CountDownLatch(1)

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            super.cancel(mayInterruptIfRunning).also { cancelled ->
                if (cancelled) this.cancelled.countDown()
            }
    }

    private class StatefulEtcdLockClient(
        private val pendFirstLock: Boolean = false,
    ): EtcdLockClient {
        val pendingAcquisition = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val grantCalls = AtomicInteger()
        val lockCalls = AtomicInteger()
        val unlockCalls = AtomicInteger()
        val revokeCalls = AtomicInteger()

        private val nextLeaseId = AtomicLong(10L)
        private val activeLockKeys = ConcurrentHashMap.newKeySet<ByteSequence>()
        private val ownershipToLockKey = ConcurrentHashMap<ByteSequence, ByteSequence>()
        private val leaseToLockKey = ConcurrentHashMap<Long, ByteSequence>()
        private val pending = AtomicReference<PendingLock?>()

        override fun singleLockKey(lockName: String): ByteSequence =
            bytes("/bluetape4k/leader/single/$lockName")

        override fun groupSlotLockKey(lockName: String, zeroBasedSlot: Int): ByteSequence =
            bytes("/bluetape4k/leader/group/$lockName/slot-$zeroBasedSlot")

        override fun grantLease(ttlSeconds: Long): CompletableFuture<Long> {
            grantCalls.incrementAndGet()
            return CompletableFuture.completedFuture(nextLeaseId.incrementAndGet())
        }

        override fun lock(lockKey: ByteSequence, leaseId: Long): CompletableFuture<ByteSequence> {
            val call = lockCalls.incrementAndGet()
            val ownershipKey = bytes("${lockKey.toString(StandardCharsets.UTF_8)}/owner-$leaseId")
            if (pendFirstLock && call == 1) {
                val future = CompletableFuture<ByteSequence>()
                pending.set(PendingLock(lockKey, leaseId, ownershipKey, future))
                pendingAcquisition.countDown()
                return future
            }
            if (!activeLockKeys.add(lockKey)) {
                return CompletableFuture()
            }
            ownershipToLockKey[ownershipKey] = lockKey
            leaseToLockKey[leaseId] = lockKey
            return CompletableFuture.completedFuture(ownershipKey)
        }

        override fun unlock(ownershipKey: ByteSequence): CompletableFuture<Unit> {
            unlockCalls.incrementAndGet()
            ownershipToLockKey.remove(ownershipKey)?.let(activeLockKeys::remove)
            return CompletableFuture.completedFuture(Unit)
        }

        override fun revokeLease(leaseId: Long): CompletableFuture<Unit> {
            revokeCalls.incrementAndGet()
            leaseToLockKey.remove(leaseId)?.let(activeLockKeys::remove)
            cleaned.countDown()
            return CompletableFuture.completedFuture(Unit)
        }

        override fun keepAliveOnce(leaseId: Long): CompletableFuture<LeaseKeepAliveResponse> =
            CompletableFuture.failedFuture(UnsupportedOperationException("keepAliveOnce is not used"))

        override fun ownershipKeys(lockKey: ByteSequence): CompletableFuture<List<ByteSequence>> =
            CompletableFuture.completedFuture(emptyList())

        fun completePendingOwnership() {
            val request = checkNotNull(pending.getAndSet(null)) { "pending ownership does not exist" }
            activeLockKeys.add(request.lockKey)
            ownershipToLockKey[request.ownershipKey] = request.lockKey
            leaseToLockKey[request.leaseId] = request.lockKey
            request.future.complete(request.ownershipKey)
        }

        fun backendCalls(): Int =
            grantCalls.get() + lockCalls.get() + unlockCalls.get() + revokeCalls.get()

        private fun bytes(value: String): ByteSequence =
            ByteSequence.from(value, StandardCharsets.UTF_8)

        private data class PendingLock(
            val lockKey: ByteSequence,
            val leaseId: Long,
            val ownershipKey: ByteSequence,
            val future: CompletableFuture<ByteSequence>,
        )
    }
}
