package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
open class KubernetesBackendLeaderElectorBenchmark {

    private val leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 60.seconds)

    private lateinit var blockingElector: LeaderElector
    private lateinit var suspendElector: SuspendLeaderElector
    private lateinit var blockingLockName: String
    private lateinit var suspendLockName: String
    private lateinit var blockingSmokeLockName: String
    private lateinit var suspendSmokeLockName: String
    private lateinit var client: KubernetesClient

    @Setup
    fun setup() = runBlocking {
        client = K3sServer.Launcher.k3s.kubernetesClient()
        blockingLockName = newLockName("blocking")
        suspendLockName = newLockName("suspend")
        blockingSmokeLockName = "$blockingLockName-smoke"
        suspendSmokeLockName = "$suspendLockName-smoke"
        cleanLease(blockingLockName)
        cleanLease(suspendLockName)
        cleanLease(blockingSmokeLockName)
        cleanLease(suspendSmokeLockName)

        val options = KubernetesLeaseOptions(
            namespace = K8S_NAMESPACE,
            retryDelay = 10.milliseconds,
            leaderOptions = leaderOptions,
        )
        blockingElector = KubernetesLeaseLeaderElector(client, options)
        suspendElector = KubernetesLeaseSuspendLeaderElector(client, options)

        require(blockingElector.runIfLeader(blockingSmokeLockName) { true } == true) {
            "Kubernetes benchmark failed blocking smoke check."
        }
        require(suspendElector.runIfLeader(suspendSmokeLockName) { true } == true) {
            "Kubernetes benchmark failed suspend smoke check."
        }
    }

    @TearDown
    fun tearDown() {
        closeResource("blockingLease") { cleanLease(blockingLockName) }
        closeResource("suspendLease") { cleanLease(suspendLockName) }
        closeResource("blockingSmokeLease") { cleanLease(blockingSmokeLockName) }
        closeResource("suspendSmokeLease") { cleanLease(suspendSmokeLockName) }
        closeResource("kubernetesClient") { client.close() }
    }

    @Benchmark
    fun blockingRunIfLeader(blackhole: Blackhole) {
        blackhole.consume(blockingElector.runIfLeader(blockingLockName) { 1 })
    }

    @Benchmark
    fun suspendRunIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(suspendElector.runIfLeader(suspendLockName) { 1 })
    }

    private fun cleanLease(lockName: String) {
        client.leases().inNamespace(K8S_NAMESPACE).withName(lockName).delete()
    }

    private fun newLockName(kind: String): String =
        "k8s-bench-$kind-${Base58.randomString(10).lowercase()}"

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { log.warn("Kubernetes benchmark resource cleanup failed. resource=$resource", it) }
    }

    companion object : KLogging() {
        private const val K8S_NAMESPACE = "default"
    }
}
