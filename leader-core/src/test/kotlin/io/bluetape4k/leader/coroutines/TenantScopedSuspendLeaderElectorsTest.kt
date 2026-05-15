package io.bluetape4k.leader.coroutines

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.coroutines.cancellation.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScopedSuspendLeaderElectorsTest {

    @Test
    fun `SuspendLeaderElector tenant scope translates state run and result calls`() = runSuspendIO {
        val delegate = RecordingSuspendLeaderElector()
        val election = delegate.forTenant("acme")

        election.state("report-job")
        election.runIfLeader("report-job") { "sync" } shouldBeEqualTo "sync"
        election.runIfLeaderResultSuspend("report-job") { "result" } shouldBeEqualTo LeaderRunResult.Elected("result")

        delegate.lockNames shouldBeEqualTo listOf(
            "tenant:acme:report-job",
            "tenant:acme:report-job",
            "tenant:acme:report-job",
        )
    }

    @Test
    fun `SuspendLeaderElector tenant scope translates slot lock name and preserves leaderId`() = runSuspendIO {
        val delegate = RecordingSuspendLeaderElector()
        val election = delegate.forTenant("acme")
        val slot = LeaderSlot("report-job", "node-1")

        election.runIfLeader(slot) { "sync" } shouldBeEqualTo "sync"
        election.runIfLeaderResultSuspend(slot) { "result" } shouldBeEqualTo LeaderRunResult.Elected("result", "node-1")

        delegate.slots shouldBeEqualTo listOf(
            LeaderSlot("tenant:acme:report-job", "node-1"),
            LeaderSlot("tenant:acme:report-job", "node-1"),
        )
    }

    @Test
    fun `SuspendLeaderElector tenant scope preserves cancellation`() = runSuspendIO {
        val delegate = RecordingSuspendLeaderElector()
        val election = delegate.forTenant("acme")

        assertFailsWith<CancellationException> {
            election.runIfLeader("report-job") {
                throw CancellationException("cancelled")
            }
        }
        delegate.lockNames shouldBeEqualTo listOf("tenant:acme:report-job")
    }

    @Test
    fun `SuspendLeaderGroupElector tenant scope translates group state and run calls`() = runSuspendIO {
        val delegate = RecordingSuspendLeaderGroupElector()
        val election = delegate.forTenant("acme")

        election.activeCount("aggregation") shouldBeEqualTo 1
        election.availableSlots("aggregation") shouldBeEqualTo 2
        election.state("aggregation") shouldBeEqualTo LeaderGroupState("tenant:acme:aggregation", 3, 1)
        election.runIfLeader("aggregation") { "done" } shouldBeEqualTo "done"
        election.runIfLeaderResultSuspend("aggregation") { "result" } shouldBeEqualTo LeaderRunResult.Elected("result")

        delegate.lockNames shouldBeEqualTo listOf(
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
            "tenant:acme:aggregation",
        )
    }

    @Test
    fun `SuspendLeaderGroupElector tenant scope translates group slot calls and preserves leaderId`() = runSuspendIO {
        val delegate = RecordingSuspendLeaderGroupElector()
        val election = delegate.forTenant("acme")
        val slot = LeaderSlot("aggregation", "node-1")

        election.runIfLeader(slot) { "sync" } shouldBeEqualTo "sync"
        election.runIfLeaderResultSuspend(slot) { "result" } shouldBeEqualTo LeaderRunResult.Elected("result", "node-1")

        delegate.slots shouldBeEqualTo listOf(
            LeaderSlot("tenant:acme:aggregation", "node-1"),
            LeaderSlot("tenant:acme:aggregation", "node-1"),
        )
    }

    private class RecordingSuspendLeaderElector : SuspendLeaderElector {
        val lockNames = mutableListOf<String>()
        val slots = mutableListOf<LeaderSlot>()

        override fun state(lockName: String): LeaderState {
            lockNames += lockName
            return LeaderState.empty(lockName)
        }

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
            lockNames += lockName
            return action()
        }

        override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
            slots += slot
            return action()
        }

        override suspend fun <T> runIfLeaderResultSuspend(
            lockName: String,
            action: suspend () -> T,
        ): LeaderRunResult<T> {
            lockNames += lockName
            return LeaderRunResult.Elected(action())
        }

        override suspend fun <T> runIfLeaderResultSuspend(
            slot: LeaderSlot,
            action: suspend () -> T,
        ): LeaderRunResult<T> {
            slots += slot
            return LeaderRunResult.Elected(action(), slot.leaderId)
        }
    }

    private class RecordingSuspendLeaderGroupElector : SuspendLeaderGroupElector {
        val lockNames = mutableListOf<String>()
        val slots = mutableListOf<LeaderSlot>()
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

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
            lockNames += lockName
            return action()
        }

        override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
            slots += slot
            return action()
        }

        override suspend fun <T> runIfLeaderResultSuspend(
            lockName: String,
            action: suspend () -> T,
        ): LeaderRunResult<T> {
            lockNames += lockName
            return LeaderRunResult.Elected(action())
        }

        override suspend fun <T> runIfLeaderResultSuspend(
            slot: LeaderSlot,
            action: suspend () -> T,
        ): LeaderRunResult<T> {
            slots += slot
            return LeaderRunResult.Elected(action(), slot.leaderId)
        }
    }
}
