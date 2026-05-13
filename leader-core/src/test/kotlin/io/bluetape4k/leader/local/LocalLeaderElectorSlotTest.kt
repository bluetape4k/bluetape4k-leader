package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [LocalLeaderElector] 의 [LeaderSlot]-aware override 계약을 검증합니다.
 *
 * ## 검증 항목 (T16 + T17)
 * - slot.leaderId → LeaderLease.auditLeaderId 전파
 * - runIfLeaderResult(slot) → Elected.leaderId == slot.leaderId
 * - 올바른 override는 LeaderElectorBridgeLog 를 호출하지 않음
 * - 재진입(reentrant) 호출에서도 leaderId 보존
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLeaderElectorSlotTest {

    companion object : KLogging()

    private val election = LocalLeaderElector()

    @BeforeEach
    fun resetBridgeLog() {
        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
    }

    private fun slot(leaderId: String = "node-a") =
        LeaderSlot("lock-${Base58.randomString(8)}", leaderId)

    // --- runIfLeaderResult(slot) 계약 ---

    @Test
    fun `runIfLeaderResult(slot) - Elected 반환 및 leaderId 전파`() {
        val s = slot("audit-node")
        val result = election.runIfLeaderResult(s) { "done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
        result.leaderId shouldBeEqualTo "audit-node"
    }

    @Test
    fun `runIfLeaderResult(slot) - action 이 null 반환해도 Elected(leaderId 포함)`() {
        val s = slot("null-node")
        val result = election.runIfLeaderResult(s) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value.shouldBeNull()
        result.leaderId shouldBeEqualTo "null-node"
    }

    // --- LeaderState.leader.auditLeaderId 전파 ---

    @Test
    fun `runIfLeader(slot) - state 안에서 auditLeaderId 가 slot leaderId 와 일치`() {
        val s = slot("state-audit")
        election.runIfLeader(s) {
            val st = election.state(s.lockName)
            st.isOccupied.shouldBeTrue()
            st.leader?.auditLeaderId shouldBeEqualTo "state-audit"
        }
    }

    @Test
    fun `runIfLeader(slot) - options nodeId 가 LeaderLease nodeId 로 전파됨`() {
        val s = slot("audit-x")
        election.runIfLeader(s) {
            val lease = election.state(s.lockName).leader
            // nodeId = options.nodeId (LeaderNodeId.Default — hostname:pid)
            (lease?.nodeId != null).shouldBeTrue()
        }
    }

    // --- bridge log 미호출 ---

    @Test
    fun `runIfLeader(slot) - 올바른 override 는 bridge log 를 호출하지 않음`() {
        election.runIfLeader(slot()) { }
        LeaderElectorBridgeLog.global().droppedAuditCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeaderResult(slot) - 올바른 override 는 result bridge log 를 호출하지 않음`() {
        election.runIfLeaderResult(slot()) { }
        LeaderElectorBridgeLog.global().droppedResultBridgeCount() shouldBeEqualTo 0L
    }

    // --- 재진입(reentrant) 보존 (T51) ---

    @Test
    fun `runIfLeaderResult(slot) - 재진입 시 outer 와 inner 모두 Elected 반환`() {
        val s = slot("reentrant-node")
        var innerResult: LeaderRunResult<String>? = null

        val outerResult = election.runIfLeaderResult(s) {
            innerResult = election.runIfLeaderResult(s) { "inner" }
            "outer"
        }

        outerResult shouldBeInstanceOf LeaderRunResult.Elected::class
        (outerResult as LeaderRunResult.Elected).leaderId shouldBeEqualTo "reentrant-node"

        innerResult shouldBeInstanceOf LeaderRunResult.Elected::class
        // reentrant path: leaderId comes from slot parameter in override
        (innerResult as LeaderRunResult.Elected).leaderId shouldBeEqualTo "reentrant-node"
    }

    @Test
    fun `runIfLeader(slot) - 재진입 inner 에서도 auditLeaderId 보존`() {
        val s = slot("reentrant-state")
        var innerAuditId: String? = null

        election.runIfLeader(s) {
            election.runIfLeader(s) {
                innerAuditId = election.state(s.lockName).leader?.auditLeaderId
            }
        }

        innerAuditId shouldBeEqualTo "reentrant-state"
    }

    // --- lockName 구분 ---

    @Test
    fun `runIfLeaderResult - 서로 다른 lockName 은 독립적으로 leaderId 를 추적`() {
        val s1 = slot("node-1")
        val s2 = LeaderSlot("lock-${Base58.randomString(8)}", "node-2")

        val r1 = election.runIfLeaderResult(s1) { 1 }
        val r2 = election.runIfLeaderResult(s2) { 2 }

        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "node-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "node-2"
    }
}
