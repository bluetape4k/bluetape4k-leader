package io.bluetape4k.leader.examples.virtualthread

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class VirtualThreadLeaderRunnerTest {

    @Test
    fun `high concurrency round elects exactly one virtual-thread runner`() {
        val report = VirtualThreadLeaderRunner(
            lockName = "virtual-thread-maintenance",
            leaderHoldTimeout = 3.seconds,
        ).runRound(VirtualThreadLeaderRunner.defaultNodeIds(64))

        report.electedNodeId.shouldNotBeNull()
        report.electedCount shouldBeEqualTo 1
        report.skippedCount shouldBeEqualTo 63
        report.nodeReports
            .single { it.status == VirtualThreadNodeStatus.ELECTED }
            .ranOnVirtualThread shouldBeEqualTo true
    }

    @Test
    fun `leader lock is released after each bounded round`() {
        val runner = VirtualThreadLeaderRunner(
            lockName = "repeatable-virtual-thread-maintenance",
            leaderHoldTimeout = 3.seconds,
        )

        val first = runner.runRound(VirtualThreadLeaderRunner.defaultNodeIds(16))
        val second = runner.runRound(VirtualThreadLeaderRunner.defaultNodeIds(16))

        first.electedCount shouldBeEqualTo 1
        second.electedCount shouldBeEqualTo 1
    }

    @Test
    fun `blank node id is rejected before scheduling virtual-thread work`() {
        assertFailsWith<IllegalArgumentException> {
            VirtualThreadLeaderRunner("invalid-node-demo").runRound(listOf("node-1", " "))
        }
    }
}
