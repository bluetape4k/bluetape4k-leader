package io.bluetape4k.leader.examples.k8soperator

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorControllerTest {

    @Test
    fun `leader pod runs reconcile workload`() {
        val workload = DemoCustomResourceWorkload()
        val controller = OperatorController(
            leaderElector = FixedLeaderElector(acquire = true),
            workload = workload,
            lockName = "operator-lock",
            podName = "operator-0",
        )

        controller.reconcileTick()

        controller.tickCount() shouldBeEqualTo 1L
        workload.reconciliationCount() shouldBeEqualTo 1L
    }

    @Test
    fun `standby pod skips reconcile workload`() {
        val workload = DemoCustomResourceWorkload()
        val controller = OperatorController(
            leaderElector = FixedLeaderElector(acquire = false),
            workload = workload,
            lockName = "operator-lock",
            podName = "operator-1",
        )

        controller.reconcileTick()

        controller.tickCount() shouldBeEqualTo 1L
        workload.reconciliationCount() shouldBeEqualTo 0L
    }

    @Test
    fun `workload returns deterministic result shape`() {
        val workload = DemoCustomResourceWorkload()
        val result = workload.reconcile(
            OperatorReconcileRequest(
                lockName = "operator-lock",
                podName = "operator-0",
                sequence = 7L,
                requestedAt = java.time.Instant.EPOCH,
            ),
        )

        result.lockName shouldBeEqualTo "operator-lock"
        result.podName shouldBeEqualTo "operator-0"
        result.sequence shouldBeEqualTo 7L
        result.revision shouldBeEqualTo 1L
    }

    private class FixedLeaderElector(
        private val acquire: Boolean,
    ) : LeaderElector {

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
            if (acquire) action() else null

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> =
            if (acquire) action().thenApply { it } else CompletableFuture.completedFuture(null)

        override fun state(lockName: String): LeaderState =
            if (acquire) LeaderState.occupied(lockName, io.bluetape4k.leader.LeaderLease("test-node"))
            else LeaderState.empty(lockName)
    }
}
