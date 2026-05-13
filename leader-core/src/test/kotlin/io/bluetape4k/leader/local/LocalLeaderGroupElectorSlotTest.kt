package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [LocalLeaderGroupElector] 의 [LeaderSlot]-aware override 계약을 검증합니다.
 *
 * ## 검증 항목 (T16 + T17 group variant)
 * - slot.leaderId → LeaderLease.auditLeaderId 전파
 * - runIfLeaderResult(slot) → Elected.leaderId == slot.leaderId
 * - 올바른 override 는 LeaderElectorBridgeLog 를 호출하지 않음
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLeaderGroupElectorSlotTest {

    companion object : KLogging()

    private val election = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 2))

    @BeforeEach
    fun resetBridgeLog() {
        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
    }

    private fun slot(leaderId: String = "node-a") =
        LeaderSlot("lock-${Base58.randomString(8)}", leaderId)

    // --- runIfLeaderResult(slot) 계약 ---

    @Test
    fun `runIfLeaderResult(slot) - Elected 반환 및 leaderId 전파`() {
        val s = slot("group-audit")
        val result = election.runIfLeaderResult(s) { "done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
        result.leaderId shouldBeEqualTo "group-audit"
    }

    @Test
    fun `runIfLeaderResult(slot) - action null 반환해도 Elected with leaderId`() {
        val s = slot("null-group")
        val result = election.runIfLeaderResult(s) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value.shouldBeNull()
        result.leaderId shouldBeEqualTo "null-group"
    }

    // --- LeaderGroupState.state().leaders 전파 ---

    @Test
    fun `runIfLeader(slot) - GroupState 에서 auditLeaderId 가 slot leaderId 와 일치`() {
        val s = slot("state-audit-group")
        election.runIfLeader(s) {
            val groupState = election.state(s.lockName)
            groupState.leaders.isNotEmpty().shouldBeTrue()
            val lease = groupState.leaders.first()
            lease.auditLeaderId shouldBeEqualTo "state-audit-group"
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

    // --- maxLeaders 범위 내 다중 선출 ---

    @Test
    fun `runIfLeader(slot) - maxLeaders=2 에서 두 노드 모두 선출 가능`() {
        val lockName = "lock-${Base58.randomString(8)}"
        val slot1 = LeaderSlot(lockName, "node-1")
        val slot2 = LeaderSlot(lockName, "node-2")

        var r1: LeaderRunResult<String>? = null
        var r2: LeaderRunResult<String>? = null

        val t1 = Thread {
            r1 = election.runIfLeaderResult(slot1) { "result-1" }
        }
        val t2 = Thread {
            r2 = election.runIfLeaderResult(slot2) { "result-2" }
        }
        t1.start(); t2.start()
        t1.join(); t2.join()

        (r1 is LeaderRunResult.Elected).shouldBeTrue()
        (r2 is LeaderRunResult.Elected).shouldBeTrue()
        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "node-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "node-2"
    }

    // --- lockName 구분 ---

    @Test
    fun `runIfLeaderResult - 서로 다른 lockName 은 독립적으로 leaderId 를 추적`() {
        val s1 = slot("group-1")
        val s2 = LeaderSlot("lock-${Base58.randomString(8)}", "group-2")

        val r1 = election.runIfLeaderResult(s1) { 1 }
        val r2 = election.runIfLeaderResult(s2) { 2 }

        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "group-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "group-2"
    }
}
