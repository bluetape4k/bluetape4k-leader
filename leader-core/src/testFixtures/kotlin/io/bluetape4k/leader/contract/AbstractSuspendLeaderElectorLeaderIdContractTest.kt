package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Backend-agnostic contract for [SuspendLeaderElector] slot-aware audit identity propagation.
 *
 * ## Verified contracts
 * - `runIfLeaderResultSuspend(slot)` → [LeaderRunResult.Elected.leaderId] == `slot.leaderId`
 * - Correct overrides do not trigger [LeaderElectorBridgeLog]
 *
 * Subclasses implement [createElector] to supply the backend-specific [SuspendLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSuspendLeaderElectorLeaderIdContractTest {

    companion object : KLoggingChannel()

    protected abstract fun createElector(options: LeaderElectionOptions): SuspendLeaderElector

    private val defaultElector: SuspendLeaderElector by lazy {
        createElector(LeaderElectionOptions.Default)
    }

    @BeforeEach
    fun resetBridgeLog() {
        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
    }

    private fun slot(leaderId: String = "node-a") =
        LeaderSlot("lock-${Base58.randomString(8)}", leaderId)

    @Test
    fun `runIfLeaderResultSuspend(slot) - Elected 반환 및 leaderId 전파`() = runSuspendIO {
        val s = slot("audit-node")
        val result = defaultElector.runIfLeaderResultSuspend(s) { "done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
        result.leaderId shouldBeEqualTo "audit-node"
    }

    @Test
    fun `runIfLeaderResultSuspend(slot) - action null 반환해도 Elected with leaderId`() = runSuspendIO {
        val s = slot("null-node")
        val result = defaultElector.runIfLeaderResultSuspend(s) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).leaderId shouldBeEqualTo "null-node"
    }

    @Test
    fun `runIfLeader(slot) - bridge log 미호출`() = runSuspendIO {
        defaultElector.runIfLeader(slot()) { }
        LeaderElectorBridgeLog.global().droppedAuditCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResultSuspend(slot) - result bridge log 미호출`() = runSuspendIO {
        defaultElector.runIfLeaderResultSuspend(slot()) { }
        LeaderElectorBridgeLog.global().droppedResultBridgeCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResultSuspend - 서로 다른 lockName 은 독립적으로 leaderId 추적`() = runSuspendIO {
        val s1 = slot("leader-1")
        val s2 = LeaderSlot("lock-${Base58.randomString(8)}", "leader-2")

        val r1 = defaultElector.runIfLeaderResultSuspend(s1) { 1 }
        val r2 = defaultElector.runIfLeaderResultSuspend(s2) { 2 }

        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "leader-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "leader-2"
    }
}
