package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScopedLeaderElectorsTest {

    @Test
    fun `TenantLockNamespace creates backend lock name`() {
        TenantLockNamespace("acme").lockName("report-job") shouldBeEqualTo "tenant:acme:report-job"
        TenantLockNamespace("acme", prefix = "app").lockName("report-job") shouldBeEqualTo "app:acme:report-job"
    }

    @Test
    fun `TenantLockNamespace rejects separator injection`() {
        assertFailsWith<IllegalArgumentException> {
            TenantLockNamespace("acme:west")
        }
        assertFailsWith<IllegalArgumentException> {
            TenantLockNamespace("acme").lockName("report:job")
        }
    }

    @Test
    fun `TenantLockNamespace reports lock name length budget`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            TenantLockNamespace("a").lockName("x".repeat(247))
        }

        thrown.message?.contains("maxLockNameLength=246") shouldBeEqualTo true
    }

    @Test
    fun `LeaderElector tenant scope translates state run async and result calls`() {
        val delegate = RecordingLeaderElector()
        val election = delegate.forTenant("acme")
        val executor = Executor { it.run() }

        election.state("report-job")
        election.runIfLeader("report-job") { "sync" } shouldBeEqualTo "sync"
        election.runAsyncIfLeader("report-job", executor) { CompletableFuture.completedFuture("async") }
            .join() shouldBeEqualTo "async"
        election.runIfLeaderResult("report-job") { "result" } shouldBeEqualTo LeaderRunResult.Elected("result")

        delegate.lockNames shouldBeEqualTo listOf(
            "tenant:acme:report-job",
            "tenant:acme:report-job",
            "tenant:acme:report-job",
            "tenant:acme:report-job",
        )
    }

    @Test
    fun `LeaderElector tenant scope translates slot lock name and preserves leaderId`() {
        val delegate = RecordingLeaderElector()
        val election = delegate.forTenant("acme")
        val executor = Executor { it.run() }
        val slot = LeaderSlot("report-job", "node-1")

        election.runIfLeader(slot) { "sync" } shouldBeEqualTo "sync"
        election.runAsyncIfLeader(slot, executor) { CompletableFuture.completedFuture("async") }
            .join() shouldBeEqualTo "async"
        election.runIfLeaderResult(slot) { "result" } shouldBeEqualTo LeaderRunResult.Elected("result", "node-1")

        delegate.slots shouldBeEqualTo listOf(
            LeaderSlot("tenant:acme:report-job", "node-1"),
            LeaderSlot("tenant:acme:report-job", "node-1"),
            LeaderSlot("tenant:acme:report-job", "node-1"),
        )
    }

    @Test
    fun `LeaderGroupElector tenant scope translates group state and run calls`() {
        val delegate = RecordingLeaderGroupElector()
        val election = delegate.forTenant("acme")

        election.activeCount("aggregation") shouldBeEqualTo 1
        election.availableSlots("aggregation") shouldBeEqualTo 2
        election.state("aggregation") shouldBeEqualTo LeaderGroupState("tenant:acme:aggregation", 3, 1)
        election.runIfLeader("aggregation") { "done" } shouldBeEqualTo "done"

        delegate.lockNames shouldBeEqualTo listOf(
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
        )
    }

    @Test
    fun `VirtualThreadLeaderElector tenant scope translates virtual-thread calls`() {
        val delegate = RecordingVirtualThreadLeaderElector()
        val election = delegate.forTenant("acme")
        val slot = LeaderSlot("report-job", "node-1")

        election.state("report-job")
        election.runAsyncIfLeader("report-job") { "done" }.toCompletableFuture().join() shouldBeEqualTo "done"
        election.runAsyncIfLeader(slot) { "slot" }.toCompletableFuture().join() shouldBeEqualTo "slot"

        delegate.lockNames shouldBeEqualTo listOf("tenant:acme:report-job", "tenant:acme:report-job")
        delegate.slots shouldBeEqualTo listOf(LeaderSlot("tenant:acme:report-job", "node-1"))
    }

    @Test
    fun `VirtualThreadLeaderGroupElector tenant scope translates group state and virtual-thread calls`() {
        val delegate = RecordingVirtualThreadLeaderGroupElector()
        val election = delegate.forTenant("acme")

        election.activeCount("aggregation") shouldBeEqualTo 1
        election.availableSlots("aggregation") shouldBeEqualTo 2
        election.state("aggregation") shouldBeEqualTo LeaderGroupState("tenant:acme:aggregation", 3, 1)
        election.runAsyncIfLeader("aggregation") { "done" }.toCompletableFuture().join() shouldBeEqualTo "done"

        delegate.lockNames shouldBeEqualTo listOf(
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
        )
    }

    private class RecordingLeaderElector : LeaderElector {
        val lockNames = mutableListOf<String>()
        val slots = mutableListOf<LeaderSlot>()

        override fun state(lockName: String): LeaderState {
            lockNames += lockName
            return LeaderState.empty(lockName)
        }

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
            lockNames += lockName
            return action()
        }

        override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? {
            slots += slot
            return action()
        }

        override fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> {
            lockNames += lockName
            return LeaderRunResult.Elected(action())
        }

        override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
            slots += slot
            return LeaderRunResult.Elected(action(), slot.leaderId)
        }

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> {
            lockNames += lockName
            return action().thenApply<T?> { it }
        }

        override fun <T> runAsyncIfLeader(
            slot: LeaderSlot,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> {
            slots += slot
            return action().thenApply<T?> { it }
        }
    }

    private class RecordingLeaderGroupElector : LeaderGroupElector {
        val lockNames = mutableListOf<String>()
        override val maxLeaders: Int = 3

        override fun activeCount(lockName: String): Int {
            lockNames += lockName
            return 1
        }

        override fun availableSlots(lockName: String): Int {
            lockNames += lockName
            return 2
        }

        override fun state(lockName: String): LeaderGroupState {
            lockNames += lockName
            return LeaderGroupState(lockName, maxLeaders, activeCount = 1)
        }

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
            lockNames += lockName
            return action()
        }

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> {
            lockNames += lockName
            return action().thenApply<T?> { it }
        }
    }

    private class RecordingVirtualThreadLeaderElector : VirtualThreadLeaderElector {
        val lockNames = mutableListOf<String>()
        val slots = mutableListOf<LeaderSlot>()

        override fun state(lockName: String): LeaderState {
            lockNames += lockName
            return LeaderState.empty(lockName)
        }

        override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> {
            lockNames += lockName
            return VirtualFuture(CompletableFuture.completedFuture(action()))
        }

        override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> {
            slots += slot
            return VirtualFuture(CompletableFuture.completedFuture(action()))
        }
    }

    private class RecordingVirtualThreadLeaderGroupElector : VirtualThreadLeaderGroupElector {
        val lockNames = mutableListOf<String>()
        override val maxLeaders: Int = 3

        override fun activeCount(lockName: String): Int {
            lockNames += lockName
            return 1
        }

        override fun availableSlots(lockName: String): Int {
            lockNames += lockName
            return 2
        }

        override fun state(lockName: String): LeaderGroupState {
            lockNames += lockName
            return LeaderGroupState(lockName, maxLeaders, activeCount = 1)
        }

        override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> {
            lockNames += lockName
            return VirtualFuture(CompletableFuture.completedFuture(action()))
        }
    }
}
