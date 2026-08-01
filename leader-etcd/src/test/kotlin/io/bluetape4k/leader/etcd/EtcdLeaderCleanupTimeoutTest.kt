package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLockClient
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EtcdLeaderCleanupTimeoutTest {

    @Test
    fun `single leader cleanup uses wait time budget`() {
        val client = FakeEtcdLockClient()
        val options = EtcdLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 123.milliseconds,
                leaseTime = 10.seconds,
            ),
        )
        val elector = EtcdLeaderElector.create(client, options)

        elector.runIfLeader("lock-a") { "ok" } shouldBeEqualTo "ok"

        client.unlockFuture.requestedTimeoutNanos shouldBeEqualTo 123.milliseconds.inWholeNanoseconds
        client.revokeFuture.requestedTimeoutNanos shouldBeEqualTo 123.milliseconds.inWholeNanoseconds
    }

    @Test
    fun `single leader cleanup falls back to retry delay when wait time is zero`() {
        val client = FakeEtcdLockClient()
        val options = EtcdLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = Duration.ZERO,
                leaseTime = 10.seconds,
            ),
            retryDelay = 321.milliseconds,
        )
        val elector = EtcdLeaderElector.create(client, options)

        elector.runIfLeader("lock-a") { "ok" } shouldBeEqualTo "ok"

        client.unlockFuture.requestedTimeoutNanos shouldBeEqualTo 321.milliseconds.inWholeNanoseconds
        client.revokeFuture.requestedTimeoutNanos shouldBeEqualTo 321.milliseconds.inWholeNanoseconds
    }

    @Test
    fun `group leader cleanup uses wait time budget`() {
        val client = FakeEtcdLockClient()
        val options = EtcdLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 234.milliseconds,
                leaseTime = 10.seconds,
            ),
        )
        val elector = EtcdLeaderGroupElector.create(client, options)

        elector.runIfLeader("lock-a") { "ok" } shouldBeEqualTo "ok"

        client.unlockFuture.requestedTimeoutNanos shouldBeEqualTo 234.milliseconds.inWholeNanoseconds
        client.revokeFuture.requestedTimeoutNanos shouldBeEqualTo 234.milliseconds.inWholeNanoseconds
    }

    @Test
    fun `single acquisition interruption revokes lease and rethrows`() {
        val client = FakeEtcdLockClient(interruptLock = true)
        val elector = EtcdLeaderElector.create(client)

        try {
            assertFailsWith<InterruptedException> {
                elector.runIfLeader("lock-a") { "should-not-run" }
            }

            Thread.currentThread().isInterrupted.shouldBeTrue()
            client.revokeCalls shouldBeGreaterOrEqualTo 1
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `group acquisition interruption revokes lease and rethrows`() {
        val client = FakeEtcdLockClient(interruptLock = true)
        val elector = EtcdLeaderGroupElector.create(
            client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 10.seconds),
            ),
        )

        try {
            assertFailsWith<InterruptedException> {
                elector.runIfLeader("lock-a") { "should-not-run" }
            }

            Thread.currentThread().isInterrupted.shouldBeTrue()
            client.revokeCalls shouldBeGreaterOrEqualTo 1
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `single acquisition preserves cancellation`() {
        val client = FakeEtcdLockClient(cancelLock = true)
        val elector = EtcdLeaderElector.create(client)

        assertFailsWith<CancellationException> {
            elector.runIfLeader("lock-a") { "should-not-run" }
        }
        client.revokeCalls shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `group acquisition preserves cancellation`() {
        val client = FakeEtcdLockClient(cancelLock = true)
        val elector = EtcdLeaderGroupElector.create(
            client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 10.seconds),
            ),
        )

        assertFailsWith<CancellationException> {
            elector.runIfLeader("lock-a") { "should-not-run" }
        }
        client.revokeCalls shouldBeGreaterOrEqualTo 1
    }

    private class FakeEtcdLockClient(
        private val interruptLock: Boolean = false,
        private val cancelLock: Boolean = false,
    ) : EtcdLockClient {
        val unlockFuture = RecordingFuture(Unit)
        val revokeFuture = RecordingFuture(Unit)
        var revokeCalls: Int = 0
            private set

        private val ownershipKey = ByteSequence.from("/locks/owner-a", StandardCharsets.UTF_8)

        override fun singleLockKey(lockName: String): ByteSequence =
            ByteSequence.from("/bluetape4k/leader/single/$lockName", StandardCharsets.UTF_8)

        override fun groupSlotLockKey(lockName: String, zeroBasedSlot: Int): ByteSequence =
            ByteSequence.from("/bluetape4k/leader/group/$lockName/slot-$zeroBasedSlot", StandardCharsets.UTF_8)

        override fun grantLease(ttlSeconds: Long): CompletableFuture<Long> =
            CompletableFuture.completedFuture(11L)

        override fun lock(lockKey: ByteSequence, leaseId: Long): CompletableFuture<ByteSequence> =
            when {
                interruptLock -> InterruptingFuture()
                cancelLock -> CompletableFuture<ByteSequence>().also { it.cancel(false) }
                else -> CompletableFuture.completedFuture(ownershipKey)
            }

        override fun unlock(ownershipKey: ByteSequence): CompletableFuture<Unit> =
            unlockFuture

        override fun revokeLease(leaseId: Long): CompletableFuture<Unit> {
            revokeCalls++
            return revokeFuture
        }

        override fun keepAliveOnce(leaseId: Long): CompletableFuture<LeaseKeepAliveResponse> =
            CompletableFuture.failedFuture(UnsupportedOperationException("keepAliveOnce is not used"))

        override fun ownershipKeys(lockKey: ByteSequence): CompletableFuture<List<ByteSequence>> =
            CompletableFuture.completedFuture(emptyList())
    }

    private class RecordingFuture<T>(
        private val value: T,
    ) : CompletableFuture<T>() {
        var requestedTimeoutNanos: Long? = null
            private set

        override fun get(timeout: Long, unit: TimeUnit): T {
            requestedTimeoutNanos = unit.toNanos(timeout)
            return value
        }
    }

    private class InterruptingFuture<T> : CompletableFuture<T>() {
        override fun get(timeout: Long, unit: TimeUnit): T =
            throw InterruptedException("interrupted acquisition")
    }
}
