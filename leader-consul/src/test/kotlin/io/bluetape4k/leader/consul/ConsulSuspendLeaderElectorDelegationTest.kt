package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.consul.internal.ConsulKvEntry
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionRenewal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConsulSuspendLeaderElectorDelegationTest {

    @Test
    fun `runIfLeaderResultSuspend returns ActionFailed for elected action failure`() = runSuspendIO {
        val client = FakeConsulLockClient()
        val elector = ConsulSuspendLeaderElector.create(client)
        val failure = LeaderElectionException("boom")

        val result = elector.runIfLeaderResultSuspend(LeaderSlot("lock-a", "audit-a")) {
            throw failure
        }

        (result is LeaderRunResult.ActionFailed) shouldBeEqualTo true
        val cause = (result as LeaderRunResult.ActionFailed).cause
        (cause is LeaderElectionException) shouldBeEqualTo true
        cause.message shouldBeEqualTo failure.message
        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `cancellation still releases and destroys session`() = runSuspendIO {
        val client = FakeConsulLockClient()
        val elector = ConsulSuspendLeaderElector.create(client)
        val started = CompletableDeferred<Unit>()

        val job = async {
            elector.runIfLeader("lock-a") {
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        job.cancelAndJoin()

        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `suspend LockAssert and LockExtender use coroutine handle delegate`() = runSuspendIO {
        val client = FakeConsulLockClient()
        val elector = ConsulSuspendLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = Duration.ZERO, leaseTime = 10.seconds),
            ),
        )

        val extended = elector.runIfLeader("lock-a") {
            LockAssert.assertLockedSuspend("lock-a")
            LockExtender.extendActiveLockSuspend(20.seconds)
        }

        extended shouldBeEqualTo true
        client.renewCalls shouldBeEqualTo 1
        client.releaseCalls shouldBeEqualTo 1
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
        val elector = ConsulSuspendLeaderElector.create(client)

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
        val elector = ConsulSuspendLeaderElector.create(client)

        elector.state("lock-a").isEmpty shouldBeEqualTo true
    }

    @Test
    fun `extendActiveLockDetailedSuspend returns BackendError when Consul renew fails`() = runSuspendIO {
        val client = FakeConsulLockClient(renewFails = true)
        val elector = ConsulSuspendLeaderElector.create(client)

        val outcome = elector.runIfLeader("lock-a") {
            LockExtender.extendActiveLockDetailedSuspend(20.seconds)
        }

        (outcome is ExtendOutcome.BackendError) shouldBeEqualTo true
        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `wait timeout returns null and destroys candidate session`() = runSuspendIO {
        val client = FakeConsulLockClient(acquireResult = false)
        val elector = ConsulSuspendLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
            ),
        )

        elector.runIfLeader("lock-a") { "should-not-run" }.shouldBeNull()

        client.acquireCalls shouldBeGreaterOrEqualTo 1
        client.releaseCalls shouldBeEqualTo 0
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `renew failure while waiting destroys candidate session`() = runSuspendIO {
        val client = FakeConsulLockClient(acquireResult = false, renewFails = true)
        val elector = ConsulSuspendLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 3500.milliseconds, leaseTime = 10.seconds),
            ),
        )

        assertFailsWith<LeaderElectionException> {
            elector.runIfLeader("lock-a") { "should-not-run" }
        }

        client.renewCalls shouldBeEqualTo 1
        client.releaseCalls shouldBeEqualTo 0
        client.destroyCalls shouldBeEqualTo 1
    }

    @Test
    fun `auto extend watchdog renews suspend lock while action is running`() = runSuspendIO {
        val client = FakeConsulLockClient()
        val elector = ConsulSuspendLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ZERO,
                    leaseTime = 10.seconds,
                    autoExtend = true,
                ),
            ),
        )

        elector.runIfLeader("lock-a") {
            delay(3500)
            "ok"
        } shouldBeEqualTo "ok"

        client.renewCalls shouldBeGreaterOrEqualTo 1
        client.releaseCalls shouldBeEqualTo 1
        client.destroyCalls shouldBeEqualTo 1
    }

    private class FakeConsulLockClient(
        private val acquireResult: Boolean = true,
        private val renewFails: Boolean = false,
        private val entry: ConsulKvEntry? = null,
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
            return CompletableFuture.completedFuture(Unit)
        }

        override fun renewSession(sessionId: ConsulSessionId): CompletableFuture<ConsulSessionRenewal> {
            renewCalls++
            if (renewFails) {
                return CompletableFuture.failedFuture(IllegalStateException("renew failed"))
            }
            return CompletableFuture.completedFuture(ConsulSessionRenewal(sessionId, Instant.now()))
        }

        override fun read(key: String): CompletableFuture<ConsulKvEntry?> =
            CompletableFuture.completedFuture(entry)
    }
}
