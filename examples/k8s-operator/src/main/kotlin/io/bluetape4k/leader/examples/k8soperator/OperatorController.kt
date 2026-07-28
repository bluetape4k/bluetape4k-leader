package io.bluetape4k.leader.examples.k8soperator

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
/**
 * `OperatorController`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property leaderElector example workflow 계약에서 `leaderElector` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property workload example workflow 계약에서 `workload` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property podName example workflow 계약에서 `podName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
class OperatorController(
    private val leaderElector: LeaderElector,
    private val workload: DemoCustomResourceWorkload,
    @Value("\${demo.operator.lock-name:cronjob-reconciler}") private val lockName: String,
    @Value("\${demo.operator.pod-name:\${HOSTNAME:local-operator}}") private val podName: String,
) {

    private val ticks = AtomicLong()

    @Scheduled(
        fixedDelayString = "\${demo.operator.fixed-delay-ms:5000}",
        initialDelayString = "\${demo.operator.initial-delay-ms:1000}",
    )
    fun reconcileTick() {
        val sequence = ticks.incrementAndGet()
        val result = leaderElector.runIfLeader(lockName) {
            workload.reconcile(
                OperatorReconcileRequest(
                    lockName = lockName,
                    podName = podName,
                    sequence = sequence,
                    requestedAt = Instant.now(),
                ),
            )
        }

        if (result == null) {
            log.debug { "operator standby. lockName=$lockName podName=$podName sequence=$sequence" }
        } else {
            log.info { "operator reconciled. lockName=$lockName podName=$podName sequence=$sequence" }
        }
    }

    fun tickCount(): Long = ticks.get()

    companion object : KLogging()
}

@Component
/**
 * `DemoCustomResourceWorkload`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 */
class DemoCustomResourceWorkload {

    private val reconciliations = AtomicLong()

    fun reconcile(request: OperatorReconcileRequest): OperatorReconcileResult {
        val revision = reconciliations.incrementAndGet()
        log.info {
            "mock custom resource reconciled. lockName=${request.lockName} podName=${request.podName} " +
                "sequence=${request.sequence} revision=$revision"
        }
        return OperatorReconcileResult(
            lockName = request.lockName,
            podName = request.podName,
            sequence = request.sequence,
            revision = revision,
            reconciledAt = Instant.now(),
        )
    }

    fun reconciliationCount(): Long = reconciliations.get()

    companion object : KLogging()
}

/**
 * `OperatorReconcileRequest`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property podName example workflow 계약에서 `podName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sequence example workflow 계약에서 `sequence` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property requestedAt example workflow 계약에서 `requestedAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class OperatorReconcileRequest(
    val lockName: String,
    val podName: String,
    val sequence: Long,
    val requestedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * `OperatorReconcileResult`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property podName example workflow 계약에서 `podName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sequence example workflow 계약에서 `sequence` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property revision example workflow 계약에서 `revision` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property reconciledAt example workflow 계약에서 `reconciledAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class OperatorReconcileResult(
    val lockName: String,
    val podName: String,
    val sequence: Long,
    val revision: Long,
    val reconciledAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
