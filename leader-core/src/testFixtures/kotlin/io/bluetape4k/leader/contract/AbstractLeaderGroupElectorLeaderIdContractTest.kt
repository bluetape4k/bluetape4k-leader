package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicReference

/**
 * Backend-agnostic contract for [LeaderGroupElector] slot-aware audit identity propagation.
 *
 * ## Verified contracts
 * - `runIfLeaderResult(slot)` → [LeaderRunResult.Elected.leaderId] == `slot.leaderId`
 * - Correct overrides do not trigger [LeaderElectorBridgeLog]
 * - Multiple nodes can be elected under `maxLeaders=2` with distinct `leaderId`s
 *
 * Subclasses implement [createElector] to supply the backend-specific [LeaderGroupElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderGroupElectorLeaderIdContractTest {

    companion object : KLogging()

    protected abstract fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector

    private val defaultElector: LeaderGroupElector by lazy {
        createElector(LeaderGroupElectionOptions(maxLeaders = 2))
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
    fun `runIfLeaderResult - maxLeaders=2 에서 두 노드 모두 선출 가능`() {
        val lockName = "lock-${Base58.randomString(8)}"
        val slot1 = LeaderSlot(lockName, "node-1")
        val slot2 = LeaderSlot(lockName, "node-2")

        val r1 = AtomicReference<LeaderRunResult<String>?>()
        val r2 = AtomicReference<LeaderRunResult<String>?>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .addAll(
                { r1.set(defaultElector.runIfLeaderResult(slot1) { "result-1" }) },
                { r2.set(defaultElector.runIfLeaderResult(slot2) { "result-2" }) },
            )
            .run()

        (r1.get() is LeaderRunResult.Elected).shouldBeTrue()
        (r2.get() is LeaderRunResult.Elected).shouldBeTrue()
        (r1.get() as LeaderRunResult.Elected).leaderId shouldBeEqualTo "node-1"
        (r2.get() as LeaderRunResult.Elected).leaderId shouldBeEqualTo "node-2"
    }

    @Test
    fun `runIfLeaderResult - 서로 다른 lockName 은 독립적으로 leaderId 추적`() {
        val s1 = slot("group-1")
        val s2 = LeaderSlot("lock-${Base58.randomString(8)}", "group-2")

        val r1 = defaultElector.runIfLeaderResult(s1) { 1 }
        val r2 = defaultElector.runIfLeaderResult(s2) { 2 }

        (r1 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "group-1"
        (r2 as LeaderRunResult.Elected).leaderId shouldBeEqualTo "group-2"
    }
}
