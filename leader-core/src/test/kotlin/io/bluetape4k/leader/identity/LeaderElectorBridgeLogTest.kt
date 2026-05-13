package io.bluetape4k.leader.identity

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectorBridgeLogTest {

    companion object : KLogging()

    private lateinit var bridgeLog: LeaderElectorBridgeLog

    @BeforeEach
    fun setup() {
        bridgeLog = LeaderElectorBridgeLog()
        LeaderElectorBridgeLog.setGlobal(bridgeLog)
    }

    private fun slot(lockName: String = "test-lock", leaderId: String = "node-a") =
        LeaderSlot(lockName, leaderId)

    @Test
    fun `warnOnBridgeUse - increments droppedAuditCount`() {
        bridgeLog.warnOnBridgeUse(FakeElector::class, slot())
        bridgeLog.droppedAuditCount() shouldBeEqualTo 1L
    }

    @Test
    fun `warnOnBridgeUse - repeated calls for same pair - counter keeps growing`() {
        val s = slot()
        repeat(5) { bridgeLog.warnOnBridgeUse(FakeElector::class, s) }
        bridgeLog.droppedAuditCount() shouldBeEqualTo 5L
    }

    @Test
    fun `warnOnBridgeUse - different impl classes - each counted`() {
        bridgeLog.warnOnBridgeUse(FakeElector::class, slot())
        bridgeLog.warnOnBridgeUse(AnotherFakeElector::class, slot())
        bridgeLog.droppedAuditCount() shouldBeEqualTo 2L
    }

    @Test
    fun `warnOnResultBridgeUse - increments droppedResultBridgeCount`() {
        bridgeLog.warnOnResultBridgeUse(FakeElector::class, slot())
        bridgeLog.droppedResultBridgeCount() shouldBeEqualTo 1L
    }

    @Test
    fun `warnOnResultBridgeUse - slot and result counters are independent`() {
        val s = slot()
        bridgeLog.warnOnBridgeUse(FakeElector::class, s)
        bridgeLog.warnOnResultBridgeUse(FakeElector::class, s)

        bridgeLog.droppedAuditCount() shouldBeEqualTo 1L
        bridgeLog.droppedResultBridgeCount() shouldBeEqualTo 1L
    }

    @Test
    fun `warnOnBridgeUse - different leaderId in slot - separate LRU entries`() {
        bridgeLog.warnOnBridgeUse(FakeElector::class, slot(leaderId = "node-a"))
        bridgeLog.warnOnBridgeUse(FakeElector::class, slot(leaderId = "node-b"))
        bridgeLog.droppedAuditCount() shouldBeEqualTo 2L
    }

    @Test
    fun `global - setGlobal replaces instance`() {
        val fresh = LeaderElectorBridgeLog()
        LeaderElectorBridgeLog.setGlobal(fresh)
        (LeaderElectorBridgeLog.global() === fresh) shouldBeEqualTo true
    }

    @Test
    fun `global - fresh instance starts at zero`() {
        bridgeLog.warnOnBridgeUse(FakeElector::class, slot())

        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
        LeaderElectorBridgeLog.global().droppedAuditCount() shouldBeEqualTo 0L
    }

    @Test
    fun `cacheSize=1 - LRU eviction causes re-warn on evicted pair`() {
        val tiny = LeaderElectorBridgeLog(cacheSize = 1)
        // first pair — warns once, enters LRU
        tiny.warnOnBridgeUse(FakeElector::class, slot(leaderId = "node-a"))
        // second pair — evicts first, warns, enters LRU
        tiny.warnOnBridgeUse(FakeElector::class, slot(leaderId = "node-b"))
        // first pair re-inserted (it was evicted) — warns again
        tiny.warnOnBridgeUse(FakeElector::class, slot(leaderId = "node-a"))

        // counter: 3 warnOnBridgeUse calls total
        tiny.droppedAuditCount() shouldBeEqualTo 3L
    }

    private class FakeElector
    private class AnotherFakeElector
}
