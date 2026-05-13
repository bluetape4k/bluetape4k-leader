package io.bluetape4k.leader.coroutines

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [LocalSuspendLeaderElector] 의 [LeaderSlot]-aware suspend override 계약을 검증합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSuspendLeaderElectorSlotTest {

    companion object : KLogging()

    private val election = LocalSuspendLeaderElector()

    @BeforeEach
    fun resetBridgeLog() {
        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
    }

    private fun slot(leaderId: String = "node-a") =
        LeaderSlot("lock-${Base58.randomString(8)}", leaderId)

    @Test
    fun `runIfLeader(slot) - suspend action 실행 및 leaderId 전파`() = runTest {
        val s = slot("suspend-audit")
        var capturedAuditId: String? = null

        election.runIfLeader(s) {
            val state = election.state(s.lockName)
            capturedAuditId = state.leader?.auditLeaderId
        }

        capturedAuditId shouldBeEqualTo "suspend-audit"
    }

    @Test
    fun `runIfLeaderResultSuspend(slot) - Elected 반환 및 leaderId 전파`() = runTest {
        val s = slot("suspend-result")
        val result = election.runIfLeaderResultSuspend(s) { "done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
        result.leaderId shouldBeEqualTo "suspend-result"
    }

    @Test
    fun `runIfLeaderResultSuspend(slot) - action null 반환해도 Elected with leaderId`() = runTest {
        val s = slot("null-suspend")
        val result = election.runIfLeaderResultSuspend(s) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value.shouldBeNull()
        result.leaderId shouldBeEqualTo "null-suspend"
    }

    @Test
    fun `runIfLeader(slot) - bridge log 를 호출하지 않음`() = runTest {
        election.runIfLeader(slot()) { }
        LeaderElectorBridgeLog.global().droppedAuditCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResultSuspend(slot) - result bridge log 를 호출하지 않음`() = runTest {
        election.runIfLeaderResultSuspend(slot()) { }
        LeaderElectorBridgeLog.global().droppedResultBridgeCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResultSuspend - 서로 다른 lockName 은 독립적으로 leaderId 를 추적`() = runTest {
        val s1 = slot("suspend-1")
        val s2 = LeaderSlot("lock-${Base58.randomString(8)}", "suspend-2")

        val r1 = election.runIfLeaderResultSuspend(s1) { 1 }
        val r2 = election.runIfLeaderResultSuspend(s2) { 2 }

        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "suspend-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "suspend-2"
    }
}
