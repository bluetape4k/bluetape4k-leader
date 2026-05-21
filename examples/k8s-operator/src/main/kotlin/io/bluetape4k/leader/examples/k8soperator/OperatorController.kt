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
