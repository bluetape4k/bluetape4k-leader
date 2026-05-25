package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.consul.internal.ConsulKvEntry
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockExtendDelegate
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionRenewal
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConsulLeaderElectorDelegationTest {

    @Test
    fun `contention returns null even when destroy cleanup fails`() {
        val client = FakeConsulLockClient(acquireResult = false, destroyFails = true)
        val elector = ConsulLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = Duration.ZERO, leaseTime = 10.seconds),
            ),
        )

        elector.runIfLeader("lock-a") { "should-not-run" }.shouldBeNull()

        client.createdSessions shouldBeEqualTo 1
        client.acquireCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `state maps owner payload to leader lease`() {
        val now = Instant.parse("2026-05-22T01:02:03Z")
        val payload = ConsulOwnerPayload(
            ownerToken = "token-a",
            auditLeaderId = "audit-a",
            nodeId = "node-a",
            electedAt = now,
            leaseUntil = now.plusSeconds(10),
        )
        val client = FakeConsulLockClient(
            entry = ConsulKvEntry(
                key = "bluetape4k/leader/single/lock-a",
                value = payload.toJson(),
                sessionId = ConsulSessionId("session-a"),
                lockIndex = 1L,
                modifyIndex = 2L,
            ),
        )
        val elector = ConsulLeaderElector.create(client)

        val state = elector.state("lock-a")

        state.lockName shouldBeEqualTo "lock-a"
        state.leader?.auditLeaderId shouldBeEqualTo "audit-a"
        state.leader?.nodeId shouldBeEqualTo "node-a"
        state.leader?.electedAt shouldBeEqualTo now
        state.leader?.leaseUntil shouldBeEqualTo now.plusSeconds(10)
    }

    @Test
    fun `state returns empty when Consul owner payload is missing`() {
        val client = FakeConsulLockClient(
            entry = ConsulKvEntry(
                key = "bluetape4k/leader/single/lock-a",
                value = null,
                sessionId = ConsulSessionId("session-a"),
                lockIndex = 1L,
                modifyIndex = 2L,
            ),
        )
        val elector = ConsulLeaderElector.create(client)

        elector.state("lock-a").isEmpty shouldBeEqualTo true
    }

    @Test
    fun `state reads with client request timeout`() {
        val future = RecordingFuture<ConsulKvEntry?>(null)
        val client = FakeConsulLockClient(
            requestTimeout = 123.milliseconds,
            readFuture = future,
        )
        val elector = ConsulLeaderElector.create(client)

        elector.state("lock-a").isEmpty shouldBeEqualTo true

        future.requestedTimeoutNanos shouldBeEqualTo 123.milliseconds.inWholeNanoseconds
    }

    @Test
    fun `interrupted min lease wait still releases and destroys session`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ZERO,
                    leaseTime = 10.seconds,
                    minLeaseTime = 1.seconds,
                ),
            ),
        )

        try {
            elector.runIfLeader("lock-a") {
                Thread.currentThread().interrupt()
                "ok"
            } shouldBeEqualTo "ok"

            client.releaseCalls shouldBeEqualTo 1
            client.destroyCalls shouldBeEqualTo 1
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `waiter session is renewed when wait time exceeds renew delay`() {
        val client = FakeConsulLockClient(acquireResult = false)
        val elector = ConsulLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 3500.milliseconds, leaseTime = 10.seconds),
            ),
        )

        elector.runIfLeader("lock-a") { "should-not-run" }.shouldBeNull()

        client.renewCalls shouldBeGreaterOrEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `isHeld reads ownership without renewing session`() {
        val sessionId = ConsulSessionId("session-a")
        val handle = ConsulLeaseHandle(
            lockName = "lock-a",
            key = "bluetape4k/leader/single/lock-a",
            sessionId = sessionId,
            ownerToken = "owner-a",
            auditLeaderId = "audit-a",
            nodeId = "node-a",
            electedAt = Instant.parse("2026-05-22T01:02:03Z"),
            leaseUntil = Instant.parse("2026-05-22T01:02:13Z"),
        )
        val client = FakeConsulLockClient(
            entry = ConsulKvEntry(
                key = handle.key,
                value = null,
                sessionId = sessionId,
                lockIndex = 1L,
                modifyIndex = 2L,
            ),
        )
        val delegate = ConsulLockExtendDelegate(client, handle)

        delegate.isHeld() shouldBeEqualTo true
        client.readCalls shouldBeEqualTo 1
        client.renewCalls shouldBeEqualTo 0
    }

    @Test
    fun `runIfLeaderResult returns ActionFailed for elected action failure`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderElector.create(client)
        val failure = LeaderElectionException("boom")

        val result = elector.runIfLeaderResult(LeaderSlot("lock-a", "audit-a")) {
            throw failure
        }

        (result is LeaderRunResult.ActionFailed) shouldBeEqualTo true
        (result as LeaderRunResult.ActionFailed).cause shouldBeEqualTo failure
        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `runAsyncIfLeaderResult rethrows cancellation instead of ActionFailed`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderElector.create(client)

        val failure = assertFailsWith<CompletionException> {
            elector.runAsyncIfLeaderResult(LeaderSlot("lock-a", "audit-a")) {
                CompletableFuture.failedFuture<String>(CancellationException("cancelled"))
            }.join()
        }

        failure.cause.shouldBeInstanceOf<CancellationException>()
        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `group runAsyncIfLeaderResult rethrows cancellation instead of ActionFailed`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderGroupElector.create(
            client,
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = io.bluetape4k.leader.LeaderGroupElectionOptions(
                    waitTime = Duration.ZERO,
                    leaseTime = 10.seconds,
                ),
            ),
        )

        val failure = assertFailsWith<CompletionException> {
            elector.runAsyncIfLeaderResult(LeaderSlot("lock-a", "audit-a")) {
                CompletableFuture.failedFuture<String>(CancellationException("cancelled"))
            }.join()
        }

        failure.cause.shouldBeInstanceOf<CancellationException>()
        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `group state reads with client request timeout`() {
        val future = RecordingFuture<ConsulKvEntry?>(null)
        val client = FakeConsulLockClient(
            requestTimeout = 77.milliseconds,
            readFuture = future,
        )
        val elector = ConsulLeaderGroupElector.create(
            client,
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 10.seconds),
            ),
        )

        elector.state("lock-a").activeCount shouldBeEqualTo 0

        future.requestedTimeoutNanos shouldBeEqualTo 77.milliseconds.inWholeNanoseconds
    }

    private class FakeConsulLockClient(
        private val acquireResult: Boolean = true,
        private val destroyFails: Boolean = false,
        private val entry: ConsulKvEntry? = null,
        override val requestTimeout: Duration = 5.seconds,
        private val readFuture: CompletableFuture<ConsulKvEntry?>? = null,
    ) : ConsulLockClient {

        var createdSessions: Int = 0
            private set
        var acquireCalls: Int = 0
            private set
        var destroyCalls: Int = 0
            private set
        var releaseCalls: Int = 0
            private set
        var renewCalls: Int = 0
            private set
        var readCalls: Int = 0
            private set

        override fun singleLockKey(lockName: String): String =
            "bluetape4k/leader/single/$lockName"

        override fun groupLockKey(lockName: String, slot: Int): String =
            "bluetape4k/leader/group/$lockName/slot-$slot"

        override fun createSession(
            name: String,
            ttl: Duration,
            lockDelay: Duration,
        ): CompletableFuture<ConsulSessionId> {
            createdSessions++
            return CompletableFuture.completedFuture(ConsulSessionId("session-$createdSessions"))
        }

        override fun acquire(
            key: String,
            sessionId: ConsulSessionId,
            ownerPayload: String,
        ): CompletableFuture<Boolean> {
            acquireCalls++
            return CompletableFuture.completedFuture(acquireResult)
        }

        override fun release(key: String, sessionId: ConsulSessionId): CompletableFuture<Boolean> {
            releaseCalls++
            return CompletableFuture.completedFuture(true)
        }

        override fun destroySession(sessionId: ConsulSessionId): CompletableFuture<Unit> {
            destroyCalls++
            return if (destroyFails) {
                CompletableFuture.failedFuture(IllegalStateException("destroy failed"))
            } else {
                CompletableFuture.completedFuture(Unit)
            }
        }

        override fun renewSession(sessionId: ConsulSessionId): CompletableFuture<ConsulSessionRenewal> =
            CompletableFuture.completedFuture(ConsulSessionRenewal(sessionId, Instant.now()))
                .also { renewCalls++ }

        override fun read(key: String): CompletableFuture<ConsulKvEntry?> {
            readCalls++
            return readFuture ?: CompletableFuture.completedFuture(entry)
        }
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
