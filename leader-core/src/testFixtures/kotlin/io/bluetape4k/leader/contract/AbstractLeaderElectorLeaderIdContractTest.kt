package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Backend-agnostic contract for [LeaderElector] slot-aware audit identity propagation.
 *
 * ## Verified contracts
 * - `runIfLeaderResult(slot)` → [LeaderRunResult.Elected.leaderId] == `slot.leaderId`
 * - Correct overrides do not trigger [LeaderElectorBridgeLog]
 *
 * Subclasses implement [createElector] to supply the backend-specific [LeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderElectorLeaderIdContractTest {

    companion object : KLogging()

    protected abstract fun createElector(options: LeaderElectionOptions): LeaderElector

    private val defaultElector: LeaderElector by lazy {
        createElector(LeaderElectionOptions.Default)
    }

    @BeforeEach
    fun resetBridgeLog() {
        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
    }

    private fun slot(leaderId: String = "node-a") =
        LeaderSlot("lock-${Base58.randomString(8)}", leaderId)

    @Test
    fun `runIfLeaderResult(slot) - Elected 반환 및 leaderId 전파`() {
        val s = slot("audit-node")
        val result = defaultElector.runIfLeaderResult(s) { "done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
        result.leaderId shouldBeEqualTo "audit-node"
    }

    @Test
    fun `runIfLeaderResult(slot) - action null 반환해도 Elected with leaderId`() {
        val s = slot("null-node")
        val result = defaultElector.runIfLeaderResult(s) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).leaderId shouldBeEqualTo "null-node"
    }

    @Test
    fun `runIfLeader(slot) - bridge log 미호출`() {
        defaultElector.runIfLeader(slot()) { }
        LeaderElectorBridgeLog.global().droppedAuditCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResult(slot) - result bridge log 미호출`() {
        defaultElector.runIfLeaderResult(slot()) { }
        LeaderElectorBridgeLog.global().droppedResultBridgeCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResult - 서로 다른 lockName 은 독립적으로 leaderId 추적`() {
        val s1 = slot("leader-1")
        val s2 = LeaderSlot("lock-${Base58.randomString(8)}", "leader-2")

        val r1 = defaultElector.runIfLeaderResult(s1) { 1 }
        val r2 = defaultElector.runIfLeaderResult(s2) { 2 }

        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "leader-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "leader-2"
    }
}
