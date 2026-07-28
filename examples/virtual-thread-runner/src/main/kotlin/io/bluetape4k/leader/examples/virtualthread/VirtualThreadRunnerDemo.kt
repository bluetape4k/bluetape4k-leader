package io.bluetape4k.leader.examples.virtualthread

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info

/**
 * `VirtualThreadRunnerDemo`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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
