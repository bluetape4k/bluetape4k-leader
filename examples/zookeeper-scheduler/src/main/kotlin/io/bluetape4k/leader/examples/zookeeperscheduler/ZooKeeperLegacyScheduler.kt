package io.bluetape4k.leader.examples.zookeeperscheduler

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import org.apache.curator.framework.CuratorFramework
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `ZooKeeperLegacyScheduler`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property config example workflow 계약에서 사용하는 속성입니다.
 */
class ZooKeeperLegacyScheduler(
    private val config: ZooKeeperSchedulerConfig,
    curator: CuratorFramework,
) {

    companion object: KLogging()

    private val elector = ZooKeeperLeaderElector(
        client = curator,
        basePath = config.basePath.value,
        options = LeaderElectionOptions(
            nodeId = config.nodeId.value,
            waitTime = config.waitTime,
            leaseTime = config.leaseTime,
            autoExtend = false,
        ),
    )

    /**
     * `runOnce` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun runOnce(
        scheduleId: SchedulerRunId,
        job: () -> List<String>,
    ): SchedulerRunReport {
        val startedAt = System.nanoTime()
        val completedSteps = elector.runIfLeader(config.lockName.value) {
            log.info { "[${config.nodeId.value}] ACQUIRED ZooKeeper leadership for ${scheduleId.value}" }
            job().also { steps ->
                steps.forEachIndexed { index, step -> step.requireNotBlank("completedSteps[$index]") }
            }
        }

        val elapsed = (System.nanoTime() - startedAt).nanoseconds
        return if (completedSteps == null) {
            log.info { "[${config.nodeId.value}] SKIPPED ${scheduleId.value} because another node is leader" }
            SchedulerRunReport(
                nodeId = config.nodeId,
                scheduleId = scheduleId,
                status = SchedulerRunStatus.SKIPPED,
                completedSteps = emptyList(),
                elapsed = elapsed,
            )
        } else {
            SchedulerRunReport(
                nodeId = config.nodeId,
                scheduleId = scheduleId,
                status = SchedulerRunStatus.EXECUTED,
                completedSteps = completedSteps,
                elapsed = elapsed,
            )
        }
    }
}

/**
 * `ZooKeeperSchedulerConfig`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property basePath example workflow 계약에서 `basePath` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property waitTime example workflow 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime example workflow 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ZooKeeperSchedulerConfig(
    val nodeId: SchedulerNodeId,
    val lockName: SchedulerLockName,
    val basePath: ZooKeeperSchedulerBasePath = ZooKeeperSchedulerBasePath("/bluetape4k/examples/zookeeper-scheduler"),
    val waitTime: Duration = 200.milliseconds,
    val leaseTime: Duration = 5.seconds,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `SchedulerNodeId`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class SchedulerNodeId(val value: String): Serializable {
    init {
        value.requireNotBlank("nodeId")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `SchedulerLockName`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class SchedulerLockName(val value: String): Serializable {
    init {
        value.requireNotBlank("lockName")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `ZooKeeperSchedulerBasePath`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class ZooKeeperSchedulerBasePath(val value: String): Serializable {
    init {
        value.requireNotBlank("basePath")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `SchedulerRunId`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class SchedulerRunId(val value: String): Serializable {
    init {
        value.requireNotBlank("scheduleId")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `SchedulerRunStatus`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property scheduleId example workflow 계약에서 사용하는 속성입니다.
 * @property status example workflow 계약에서 사용하는 속성입니다.
 * @property completedSteps example workflow 계약에서 사용하는 속성입니다.
 * @property elapsed example workflow 계약에서 사용하는 속성입니다.
 */
enum class SchedulerRunStatus {
    EXECUTED,
    SKIPPED,
}

/**
 * `SchedulerRunReport`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property scheduleId example workflow 계약에서 `scheduleId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property completedSteps example workflow 계약에서 `completedSteps` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property elapsed example workflow 계약에서 `elapsed` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class SchedulerRunReport(
    val nodeId: SchedulerNodeId,
    val scheduleId: SchedulerRunId,
    val status: SchedulerRunStatus,
    val completedSteps: List<String>,
    val elapsed: Duration,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
