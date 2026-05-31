package io.bluetape4k.leader.examples.virtualthread

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info

/**
 * Runnable virtual-thread leader election demo.
 */
object VirtualThreadRunnerDemo: KLogging() {

    @JvmStatic
    fun main(args: Array<String>) {
        val report = VirtualThreadLeaderRunner("virtual-thread-maintenance").runRound()

        log.info { "=== virtual-thread leader runner result ===" }
        log.info { "lock=${report.lockName}, elected=${report.electedNodeId}, skipped=${report.skippedCount}" }
        report.nodeReports.forEach { nodeReport ->
            log.info {
                "[${nodeReport.nodeId}] ${nodeReport.status} virtualThread=${nodeReport.ranOnVirtualThread}"
            }
        }
    }
}
