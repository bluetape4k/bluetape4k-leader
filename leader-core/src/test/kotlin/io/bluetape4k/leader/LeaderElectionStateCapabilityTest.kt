package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.forTenant
import io.bluetape4k.leader.coroutines.withListeners
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class LeaderElectionStateCapabilityTest {

    @Test
    fun `default capability is false and synchronous decorators preserve it`() {
        val unsupported = UnsupportedLeaderElector()

        unsupported.supportsAuditLeaderState.shouldBeFalse()
        unsupported.withListeners().supportsAuditLeaderState.shouldBeFalse()
        unsupported.forTenant("tenant-a").supportsAuditLeaderState.shouldBeFalse()
    }

    @Test
    fun `local capability is true and synchronous decorators preserve it`() {
        val local = LocalLeaderElector()

        local.supportsAuditLeaderState.shouldBeTrue()
        local.withListeners().supportsAuditLeaderState.shouldBeTrue()
        local.forTenant("tenant-a").supportsAuditLeaderState.shouldBeTrue()
    }

    @Test
    fun `suspend decorators preserve local capability`() {
        val local = LocalSuspendLeaderElector()

        local.supportsAuditLeaderState.shouldBeTrue()
        local.withListeners().supportsAuditLeaderState.shouldBeTrue()
        local.forTenant("tenant-a").supportsAuditLeaderState.shouldBeTrue()
    }

    @Test
    fun `listening decorator preserves slot audit identity across synchronous APIs`() {
        val election = LocalLeaderElector().withListeners()
        val slot = LeaderSlot("listener-sync-capability", "process-a")

        val observed = election.runIfLeader(slot) {
            election.state(slot.lockName).leader?.auditLeaderId
        }
        val result = election.runIfLeaderResult(slot) {
            election.state(slot.lockName).leader?.auditLeaderId
        }

        observed shouldBeEqualTo slot.leaderId
        result shouldBeEqualTo LeaderRunResult.Elected(slot.leaderId, slot.leaderId)
    }

    @Test
    fun `listening decorator preserves slot audit identity across asynchronous APIs`() {
        val directExecutor = Executor { it.run() }
        val election = LocalLeaderElector().withListeners()
        val slot = LeaderSlot("listener-async-capability", "process-b")

        val observed = election.runAsyncIfLeader(slot, directExecutor) {
            CompletableFuture.completedFuture(election.state(slot.lockName).leader?.auditLeaderId)
        }.join()
        val result = election.runAsyncIfLeaderResult(slot, directExecutor) {
            CompletableFuture.completedFuture(election.state(slot.lockName).leader?.auditLeaderId)
        }.join()

        observed shouldBeEqualTo slot.leaderId
        result shouldBeEqualTo LeaderRunResult.Elected(slot.leaderId, slot.leaderId)
    }

    @Test
    fun `listening suspend decorator preserves slot audit identity across result APIs`() = runSuspendIO {
        val election = LocalSuspendLeaderElector().withListeners()
        val slot = LeaderSlot("listener-suspend-capability", "process-c")

        val observed = election.runIfLeader(slot) {
            election.state(slot.lockName).leader?.auditLeaderId
        }
        val result = election.runIfLeaderResultSuspend(slot) {
            election.state(slot.lockName).leader?.auditLeaderId
        }

        observed shouldBeEqualTo slot.leaderId
        result shouldBeEqualTo LeaderRunResult.Elected(slot.leaderId, slot.leaderId)
    }

    private class UnsupportedLeaderElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }
}
