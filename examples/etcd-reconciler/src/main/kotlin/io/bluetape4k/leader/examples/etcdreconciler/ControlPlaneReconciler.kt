package io.bluetape4k.leader.examples.etcdreconciler

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.etcd.jetcd.Client
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `ControlPlaneReconciler`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 사용하는 속성입니다.
 */
class ControlPlaneReconciler(
    val nodeId: String,
    client: Client,
    private val lockName: String,
    keyPrefix: String = "/bluetape4k/examples/etcd-reconciler",
    waitTime: Duration = 500.milliseconds,
    leaseTime: Duration = 10.seconds,
) {

    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object: KLogging()

    private val elector = EtcdLeaderElector(
        client = client,
        options = EtcdLeaderElectionOptions(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(
                nodeId = nodeId,
                waitTime = waitTime,
                leaseTime = leaseTime,
                autoExtend = true,
            ),
        ),
    )

    /**
     * `reconcile` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun reconcile(workSupplier: () -> List<String>): ReconcileReport {
        val appliedResources = elector.runIfLeader(lockName) {
            log.info { "[$nodeId] ACQUIRED etcd leadership for lock=$lockName" }
            workSupplier()
        }

        return if (appliedResources == null) {
            log.info { "[$nodeId] SKIPPED reconcile because another node is leader" }
            ReconcileReport(
                nodeId = nodeId,
                status = ReconcileStatus.SKIPPED,
                appliedResources = emptyList(),
            )
        } else {
            ReconcileReport(
                nodeId = nodeId,
                status = ReconcileStatus.APPLIED,
                appliedResources = appliedResources,
            )
        }
    }
}

/**
 * `ReconcileStatus`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property status example workflow 계약에서 사용하는 속성입니다.
 * @property appliedResources example workflow 계약에서 사용하는 속성입니다.
 */
enum class ReconcileStatus {
    APPLIED,
    SKIPPED,
}

/**
 * `ReconcileReport`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property appliedResources example workflow 계약에서 `appliedResources` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ReconcileReport(
    val nodeId: String,
    val status: ReconcileStatus,
    val appliedResources: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
