package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `ServiceMaintenanceCoordinator`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property config example workflow 계약에서 사용하는 속성입니다.
 */
class ServiceMaintenanceCoordinator(
    private val config: ServiceMaintenanceConfig,
    endpoint: ConsulEndpoint,
) {

    companion object : KLogging()

    private val elector = ConsulLeaderElector(
        endpoint = endpoint,
        options = ConsulLeaderElectionOptions(
            keyPrefix = config.keyPrefix.value,
            leaderOptions = LeaderElectionOptions(
                nodeId = config.nodeId.value,
                waitTime = config.waitTime,
                leaseTime = config.leaseTime,
                autoExtend = true,
            ),
        ),
    )

    /**
     * `performMaintenance` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun performMaintenance(workSupplier: () -> List<String>): MaintenanceReport {
        val completedSteps = elector.runIfLeader(config.lockName.value) {
            log.info { "[${config.nodeId.value}] ACQUIRED Consul leadership for lock=${config.lockName.value}" }
            workSupplier()
        }

        return if (completedSteps == null) {
            log.info { "[${config.nodeId.value}] SKIPPED maintenance because another node is leader" }
            MaintenanceReport(
                nodeId = config.nodeId,
                status = MaintenanceStatus.SKIPPED,
                completedSteps = emptyList(),
            )
        } else {
            MaintenanceReport(
                nodeId = config.nodeId,
                status = MaintenanceStatus.PERFORMED,
                completedSteps = completedSteps,
            )
        }
    }
}

/**
 * `ServiceMaintenanceConfig`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix example workflow 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property waitTime example workflow 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime example workflow 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ServiceMaintenanceConfig(
    val nodeId: MaintenanceNodeId,
    val lockName: MaintenanceLockName,
    val keyPrefix: MaintenanceKeyPrefix = MaintenanceKeyPrefix("bluetape4k/examples/consul-maintenance"),
    val waitTime: Duration = 500.milliseconds,
    val leaseTime: Duration = 10.seconds,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `MaintenanceNodeId`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class MaintenanceNodeId(val value: String) : Serializable {
    init {
        value.requireNotBlank("nodeId")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `MaintenanceLockName`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class MaintenanceLockName(val value: String) : Serializable {
    init {
        value.requireNotBlank("lockName")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `MaintenanceKeyPrefix`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property value example workflow 계약에서 사용하는 속성입니다.
 */
@JvmInline
value class MaintenanceKeyPrefix(val value: String) : Serializable {
    init {
        value.requireNotBlank("keyPrefix")
    }

    override fun toString(): String = value

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `MaintenanceStatus`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property status example workflow 계약에서 사용하는 속성입니다.
 * @property completedSteps example workflow 계약에서 사용하는 속성입니다.
 */
enum class MaintenanceStatus {
    PERFORMED,
    SKIPPED,
}

/**
 * `MaintenanceReport`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property completedSteps example workflow 계약에서 `completedSteps` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class MaintenanceReport(
    val nodeId: MaintenanceNodeId,
    val status: MaintenanceStatus,
    val completedSteps: List<String>,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
