package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `LeaderDispatchScheduler`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 사용하는 속성입니다.
 */
class LeaderDispatchScheduler(
    val nodeId: String,
    connection: StatefulRedisConnection<String, String>,
    private val lockName: String,
    waitTime: Duration = 500.milliseconds,
    leaseTime: Duration = 10.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object: KLogging()

    private val elector = LettuceLeaderElector(
        connection,
        LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime),
    )

    fun schedule(workSupplier: () -> List<String>): DispatchReport {
        val scheduledItems = elector.runIfLeader(lockName) {
            log.info { "[$nodeId] SCHEDULED dispatch under lock=$lockName" }
            workSupplier()
        }

        return if (scheduledItems == null) {
            log.info { "[$nodeId] REJECTED dispatch because another node is leader" }
            DispatchReport(
                nodeId = nodeId,
                status = RateLimiterDemoStatus.REJECTED,
                scheduledItems = emptyList(),
            )
        } else {
            DispatchReport(
                nodeId = nodeId,
                status = RateLimiterDemoStatus.SCHEDULED,
                scheduledItems = scheduledItems,
            )
        }
    }
}

/**
 * `DispatchReport`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property scheduledItems example workflow 계약에서 `scheduledItems` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class DispatchReport(
    val nodeId: String,
    val status: RateLimiterDemoStatus,
    val scheduledItems: List<String>,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 7331738806090205222L
    }
}
