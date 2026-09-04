package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    fun `interrupted single acquisition cleans up session and rethrows`() {
        val client = FakeConsulLockClient(interruptAcquire = true)
        val elector = ConsulLeaderElector.create(client)

        try {
            assertFailsWith<InterruptedException> {
                elector.runIfLeader("lock-a") { "should-not-run" }
            }

            Thread.currentThread().isInterrupted.shouldBeTrue()
            client.destroyCalls shouldBeEqualTo 1
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interrupted group acquisition cleans up session and rethrows`() {
        val client = FakeConsulLockClient(interruptAcquire = true)
        val elector = ConsulLeaderGroupElector.create(
            client,
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 10.seconds),
            ),
        )

        try {
            assertFailsWith<InterruptedException> {
                elector.runIfLeader("lock-a") { "should-not-run" }
            }

            Thread.currentThread().isInterrupted.shouldBeTrue()
            client.destroyCalls shouldBeEqualTo 1
        } finally {
            Thread.interrupted()
        }
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
    fun `interrupted min lease wait releases session and rethrows`() {
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
            assertFailsWith<InterruptedException> {
                elector.runIfLeader("lock-a") {
                    Thread.currentThread().interrupt()
                    "ok"
                }
            }

            Thread.currentThread().isInterrupted.shouldBeTrue()
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
    fun `extend returns NotHeld when Consul KV ownership moved to another session`() {
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
                sessionId = ConsulSessionId("session-b"),
                lockIndex = 3L,
                modifyIndex = 4L,
            ),
        )
        val delegate = ConsulLockExtendDelegate(client, handle)

        delegate.extend(20.seconds) shouldBeEqualTo ExtendOutcome.NotHeld

        client.readCalls shouldBeEqualTo 1
        delegate.lastExtendDeadline.get() shouldBeEqualTo Instant.EPOCH
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
    fun `runAsyncIfLeaderResult 취소 후 늦게 획득한 single lease를 정리한다`() {
        val acquisition = CompletableFuture<Boolean>()
        val acquireStarted = CountDownLatch(1)
        val releaseObserved = CountDownLatch(1)
        val client = FakeConsulLockClient(
            acquireFuture = acquisition,
            acquireStarted = acquireStarted,
            releaseObserved = releaseObserved,
        )
        val elector = ConsulLeaderElector.create(client)
        val executor = Executors.newFixedThreadPool(2)
        val actionInvoked = AtomicBoolean()

        try {
            val result = elector.runAsyncIfLeaderResult(LeaderSlot("lock-late-single", "audit-a"), executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("실행되면 안 됨")
            }

            acquireStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            acquisition.complete(true)

            releaseObserved.await(2, TimeUnit.SECONDS).shouldBeTrue()
            actionInvoked.get() shouldBeEqualTo false
            client.releaseCalls shouldBeEqualTo 1
            client.destroyCalls shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeaderResult 취소 후 늦게 획득한 group lease를 정리한다`() {
        val acquisition = CompletableFuture<Boolean>()
        val acquireStarted = CountDownLatch(1)
        val releaseObserved = CountDownLatch(1)
        val client = FakeConsulLockClient(
            acquireFuture = acquisition,
            acquireStarted = acquireStarted,
            releaseObserved = releaseObserved,
        )
        val elector = ConsulLeaderGroupElector.create(
            client,
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 10.seconds),
            ),
        )
        val executor = Executors.newFixedThreadPool(2)
        val actionInvoked = AtomicBoolean()

        try {
            val result = elector.runAsyncIfLeaderResult(LeaderSlot("lock-late-group", "audit-a"), executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("실행되면 안 됨")
            }

            acquireStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            acquisition.complete(true)

            releaseObserved.await(2, TimeUnit.SECONDS).shouldBeTrue()
            actionInvoked.get() shouldBeEqualTo false
            client.releaseCalls shouldBeEqualTo 1
            client.destroyCalls shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeader cleanup runs after caller executor shutdown`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderElector.create(
            client,
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ZERO,
                    leaseTime = 10.seconds,
                    autoExtend = true,
                ),
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val resultFuture = elector.runAsyncIfLeader("lock-a", executor) {
                actionStarted.countDown()
                actionFuture
            }
            actionStarted.await(3, TimeUnit.SECONDS).shouldBeTrue()
            executor.shutdown()

            actionFuture.complete("done")

            resultFuture.get(3, TimeUnit.SECONDS) shouldBeEqualTo "done"
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
            client.releaseCalls shouldBeEqualTo 2
            client.destroyCalls shouldBeEqualTo 2
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeader releases acquired single lease when second executor submission is rejected`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderElector.create(client)
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }

        try {
            val resultFuture = runCatching {
                elector.runAsyncIfLeader("lock-a", executor) {
                    CompletableFuture.completedFuture("should-not-run")
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
            client.releaseCalls shouldBeEqualTo 2
            client.destroyCalls shouldBeEqualTo 2
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `runAsyncIfLeader releases acquired group slot when second executor submission is rejected`() {
        val client = FakeConsulLockClient()
        val elector = ConsulLeaderGroupElector.create(
            client,
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 1, leaseTime = 10.seconds),
            ),
        )
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }

        try {
            val resultFuture = runCatching {
                elector.runAsyncIfLeader("lock-a", executor) {
                    CompletableFuture.completedFuture("should-not-run")
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            elector.runIfLeader("lock-a") { "reacquired" } shouldBeEqualTo "reacquired"
            client.releaseCalls shouldBeEqualTo 2
            client.destroyCalls shouldBeEqualTo 2
        } finally {
            worker.shutdownNow()
        }
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
    fun `group acquisition caps slot probes per retry under saturation`() {
        val client = FakeConsulLockClient(acquireResult = false)
        val elector = ConsulLeaderGroupElector.create(
            client,
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 64,
                    waitTime = 120.milliseconds,
                    leaseTime = 10.seconds,
                ),
            ),
        )

        elector.runIfLeader("lock-a") { "should-not-run" }.shouldBeNull()

        client.acquireCalls shouldBeLessOrEqualTo (CONSUL_GROUP_SLOT_PROBE_LIMIT * 8)
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
        private val interruptAcquire: Boolean = false,
        private val acquireFuture: CompletableFuture<Boolean>? = null,
        private val acquireStarted: CountDownLatch? = null,
        private val releaseObserved: CountDownLatch? = null,
    ) : ConsulLockClient {

        private var currentEntry: ConsulKvEntry? = entry

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
            acquireStarted?.countDown()
            if (interruptAcquire) {
                return InterruptingFuture()
            }
            acquireFuture?.let { return it }
            if (acquireResult) {
                currentEntry = ConsulKvEntry(
                    key = key,
                    value = ownerPayload,
                    sessionId = sessionId,
                    lockIndex = 1L,
                    modifyIndex = 1L,
                )
            }
            return CompletableFuture.completedFuture(acquireResult)
        }

        override fun release(key: String, sessionId: ConsulSessionId): CompletableFuture<Boolean> {
            releaseCalls++
            releaseObserved?.countDown()
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
            return readFuture ?: CompletableFuture.completedFuture(currentEntry)
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

    private class InterruptingFuture<T> : CompletableFuture<T>() {
        override fun get(timeout: Long, unit: TimeUnit): T =
            throw InterruptedException("interrupted acquisition")
    }
}
