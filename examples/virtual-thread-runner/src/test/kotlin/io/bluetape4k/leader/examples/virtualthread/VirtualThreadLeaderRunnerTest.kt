package io.bluetape4k.leader.examples.virtualthread

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class VirtualThreadLeaderRunnerTest {

    companion object: KLogging()

    @Test
    fun `high concurrency round elects exactly one virtual-thread runner`() {
        val nodeSize = 64
        val report = VirtualThreadLeaderRunner(
            lockName = "virtual-thread-maintenance",
            leaderHoldTimeout = 3.seconds,
        ).runRound(VirtualThreadLeaderRunner.defaultNodeIds(nodeSize))

        log.debug { "report=$report" }
        report.electedNodeId.shouldNotBeNull()
        report.electedCount shouldBeEqualTo 1
        report.skippedCount shouldBeEqualTo nodeSize - 1

        report.nodeReports
            .single { it.status == VirtualThreadNodeStatus.ELECTED }
            .ranOnVirtualThread.shouldBeTrue()
    }

    @Test
    fun `leader lock is released after each bounded round`() {
        val runner = VirtualThreadLeaderRunner(
            lockName = "repeatable-virtual-thread-maintenance",
            leaderHoldTimeout = 3.seconds,
        )
        val nodeSize = 16
        val first = runner.runRound(VirtualThreadLeaderRunner.defaultNodeIds(nodeSize))
        val second = runner.runRound(VirtualThreadLeaderRunner.defaultNodeIds(nodeSize))

        log.debug { "first=$first" }
        log.debug { "second=$second" }
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
