package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLockClient
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
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

    private class FakeEtcdLockClient : EtcdLockClient {
        val unlockFuture = RecordingFuture(Unit)
        val revokeFuture = RecordingFuture(Unit)

        private val ownershipKey = ByteSequence.from("/locks/owner-a", StandardCharsets.UTF_8)

        override fun singleLockKey(lockName: String): ByteSequence =
            ByteSequence.from("/bluetape4k/leader/single/$lockName", StandardCharsets.UTF_8)

        override fun groupSlotLockKey(lockName: String, zeroBasedSlot: Int): ByteSequence =
            ByteSequence.from("/bluetape4k/leader/group/$lockName/slot-$zeroBasedSlot", StandardCharsets.UTF_8)

        override fun grantLease(ttlSeconds: Long): CompletableFuture<Long> =
            CompletableFuture.completedFuture(11L)

        override fun lock(lockKey: ByteSequence, leaseId: Long): CompletableFuture<ByteSequence> =
            CompletableFuture.completedFuture(ownershipKey)

        override fun unlock(ownershipKey: ByteSequence): CompletableFuture<Unit> =
            unlockFuture

        override fun revokeLease(leaseId: Long): CompletableFuture<Unit> =
            revokeFuture

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
}
