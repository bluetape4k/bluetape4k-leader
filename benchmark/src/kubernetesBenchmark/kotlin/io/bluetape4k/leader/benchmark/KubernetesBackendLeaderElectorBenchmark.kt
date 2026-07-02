package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.time.Clock
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
class KubernetesBackendLeaderElectorBenchmark {

    private val leaderOptions = LeaderElectionOptions(waitTime = 0.seconds, leaseTime = 60.seconds)
    private val clock = Clock.systemUTC()

    private lateinit var blockingElector: LeaderElector
    private lateinit var suspendElector: SuspendLeaderElector
    private lateinit var blockingSmokeLockName: String
    private lateinit var suspendSmokeLockName: String
    private lateinit var client: KubernetesClient

    @Setup
    fun setup() = runBlocking {
        client = K3sServer.Launcher.k3s.kubernetesClient()
        blockingSmokeLockName = newLockName("blocking-smoke")
        suspendSmokeLockName = newLockName("suspend-smoke")
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
        closeResource("blockingSmokeLease") { cleanLease(blockingSmokeLockName) }
        closeResource("suspendSmokeLease") { cleanLease(suspendSmokeLockName) }
        closeResource("kubernetesClient") { client.close() }
    }

    @Benchmark
    fun blockingFreshAcquire(state: KubernetesFreshLeaseState, blackhole: Blackhole) {
        blackhole.consume(blockingElector.runIfLeader(state.blockingLockName) { 1 })
    }

    @Benchmark
    fun blockingPreHeldSkip(state: KubernetesActiveHolderLeaseState, blackhole: Blackhole) {
        blackhole.consume(blockingElector.runIfLeader(state.blockingLockName) { 1 })
    }

    @Benchmark
    fun blockingExpiredTakeover(state: KubernetesExpiredHolderLeaseState, blackhole: Blackhole) {
        blackhole.consume(blockingElector.runIfLeader(state.blockingLockName) { 1 })
    }

    @Benchmark
    fun blockingLeaseRenewalUpdate(state: KubernetesRenewalLeaseState, blackhole: Blackhole) {
        blackhole.consume(renewLease(state.blockingLockName, state.holderIdentity))
    }

    @Benchmark
    fun blockingResourceVersionConflict(state: KubernetesConflictLeaseState, blackhole: Blackhole) {
        blackhole.consume(updateWithConflict(state.blockingStaleLease))
    }

    @Benchmark
    fun suspendFreshAcquire(state: KubernetesFreshLeaseState, blackhole: Blackhole) = runBlocking {
        blackhole.consume(suspendElector.runIfLeader(state.suspendLockName) { 1 })
    }

    @Benchmark
    fun suspendPreHeldSkip(state: KubernetesActiveHolderLeaseState, blackhole: Blackhole) = runBlocking {
        blackhole.consume(suspendElector.runIfLeader(state.suspendLockName) { 1 })
    }

    @Benchmark
    fun suspendExpiredTakeover(state: KubernetesExpiredHolderLeaseState, blackhole: Blackhole) = runBlocking {
        blackhole.consume(suspendElector.runIfLeader(state.suspendLockName) { 1 })
    }

    @Benchmark
    fun suspendLeaseRenewalUpdate(state: KubernetesRenewalLeaseState, blackhole: Blackhole) = runBlocking {
        blackhole.consume(withContext(Dispatchers.IO) { renewLease(state.suspendLockName, state.holderIdentity) })
    }

    @Benchmark
    fun suspendResourceVersionConflict(state: KubernetesConflictLeaseState, blackhole: Blackhole) = runBlocking {
        blackhole.consume(withContext(Dispatchers.IO) { updateWithConflict(state.suspendStaleLease) })
    }

    fun cleanLease(lockName: String) {
        client.leases().inNamespace(K8S_NAMESPACE).withName(lockName).delete()
    }

    fun newLockName(kind: String): String =
        "k8s-bench-$kind-${Base58.randomString(10).lowercase()}"

    fun putLease(
        lockName: String,
        holderIdentity: String?,
        renewTime: ZonedDateTime,
        leaseDurationSeconds: Int = 60,
        transitions: Int = 0,
    ): Lease {
        val desired = LeaseBuilder()
            .withNewMetadata()
            .withName(lockName)
            .withNamespace(K8S_NAMESPACE)
            .endMetadata()
            .withNewSpec()
            .withHolderIdentity(holderIdentity)
            .withLeaseDurationSeconds(leaseDurationSeconds)
            .withAcquireTime(renewTime.minusSeconds(5))
            .withRenewTime(renewTime)
            .withLeaseTransitions(transitions)
            .endSpec()
            .build()

        val current = lease(lockName)
        return if (current == null) {
            client.leases().inNamespace(K8S_NAMESPACE).resource(desired).create()
        } else {
            val updated = LeaseBuilder(desired)
                .editMetadata()
                .withResourceVersion(current.metadata.resourceVersion)
                .endMetadata()
                .build()
            client.resource(updated).update()
        }
    }

    fun activeLease(lockName: String, holderIdentity: String): Lease =
        putLease(
            lockName = lockName,
            holderIdentity = holderIdentity,
            renewTime = now(),
            leaseDurationSeconds = 60,
        )

    fun expiredLease(lockName: String, holderIdentity: String): Lease =
        putLease(
            lockName = lockName,
            holderIdentity = holderIdentity,
            renewTime = now().minusSeconds(120),
            leaseDurationSeconds = 1,
            transitions = 1,
        )

    fun renewLease(lockName: String, holderIdentity: String): Lease {
        val current = requireNotNull(lease(lockName)) { "Lease fixture must exist. lockName=$lockName" }
        val renewed = LeaseBuilder(current)
            .withSpec(
                LeaseSpecBuilder(current.spec)
                    .withHolderIdentity(holderIdentity)
                    .withRenewTime(now())
                    .build(),
            )
            .build()
        return client.resource(renewed).update()
    }

    fun staleConflictLease(lockName: String, holderIdentity: String): Lease {
        val stale = expiredLease(lockName, "$holderIdentity-stale")
        val serverWinner = LeaseBuilder(stale)
            .withSpec(
                LeaseSpecBuilder(stale.spec)
                    .withHolderIdentity("$holderIdentity-server-winner")
                    .withRenewTime(now())
                    .withLeaseDurationSeconds(60)
                    .build(),
            )
            .build()
        client.resource(serverWinner).update()

        return LeaseBuilder(stale)
            .withSpec(
                LeaseSpecBuilder(stale.spec)
                    .withHolderIdentity(holderIdentity)
                    .withRenewTime(now())
                    .withLeaseDurationSeconds(60)
                    .build(),
            )
            .build()
    }

    fun updateWithConflict(staleLease: Lease): Boolean =
        try {
            client.resource(staleLease).update()
            false
        } catch (e: KubernetesClientException) {
            if (e.code == CONFLICT) true else throw e
        }

    private fun lease(lockName: String): Lease? =
        client.leases().inNamespace(K8S_NAMESPACE).withName(lockName).get()

    private fun now(): ZonedDateTime =
        ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure {
                log.warn(it) { "Kubernetes benchmark resource cleanup failed. resource=$resource" }
            }
    }

    companion object : KLogging() {
        private const val CONFLICT = 409
        internal const val K8S_NAMESPACE = "default"
    }
}

@State(Scope.Thread)
open class KubernetesFreshLeaseState {

    lateinit var blockingLockName: String
    lateinit var suspendLockName: String

    @Setup(Level.Trial)
    fun setup(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        blockingLockName = benchmark.newLockName("blocking-fresh")
        suspendLockName = benchmark.newLockName("suspend-fresh")
    }

    @Setup(Level.Invocation)
    fun setupInvocation(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.cleanLease(blockingLockName)
        benchmark.cleanLease(suspendLockName)
    }

    @TearDown(Level.Trial)
    fun tearDown(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.cleanLease(blockingLockName)
        benchmark.cleanLease(suspendLockName)
    }
}

@State(Scope.Thread)
open class KubernetesActiveHolderLeaseState {

    lateinit var blockingLockName: String
    lateinit var suspendLockName: String
    private val holderIdentity = "benchmark-active-holder"

    @Setup(Level.Trial)
    fun setup(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        blockingLockName = benchmark.newLockName("blocking-skip")
        suspendLockName = benchmark.newLockName("suspend-skip")
        benchmark.activeLease(blockingLockName, holderIdentity)
        benchmark.activeLease(suspendLockName, holderIdentity)
    }

    @TearDown(Level.Trial)
    fun tearDown(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.cleanLease(blockingLockName)
        benchmark.cleanLease(suspendLockName)
    }
}

@State(Scope.Thread)
open class KubernetesExpiredHolderLeaseState {

    lateinit var blockingLockName: String
    lateinit var suspendLockName: String
    private val holderIdentity = "benchmark-expired-holder"

    @Setup(Level.Trial)
    fun setup(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        blockingLockName = benchmark.newLockName("blocking-takeover")
        suspendLockName = benchmark.newLockName("suspend-takeover")
    }

    @Setup(Level.Invocation)
    fun setupInvocation(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.expiredLease(blockingLockName, holderIdentity)
        benchmark.expiredLease(suspendLockName, holderIdentity)
    }

    @TearDown(Level.Trial)
    fun tearDown(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.cleanLease(blockingLockName)
        benchmark.cleanLease(suspendLockName)
    }
}

@State(Scope.Thread)
open class KubernetesRenewalLeaseState {

    lateinit var blockingLockName: String
    lateinit var suspendLockName: String
    val holderIdentity = "benchmark-renewal-holder"

    @Setup(Level.Trial)
    fun setup(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        blockingLockName = benchmark.newLockName("blocking-renewal")
        suspendLockName = benchmark.newLockName("suspend-renewal")
    }

    @Setup(Level.Invocation)
    fun setupInvocation(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.activeLease(blockingLockName, holderIdentity)
        benchmark.activeLease(suspendLockName, holderIdentity)
    }

    @TearDown(Level.Trial)
    fun tearDown(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.cleanLease(blockingLockName)
        benchmark.cleanLease(suspendLockName)
    }
}

@State(Scope.Thread)
open class KubernetesConflictLeaseState {

    lateinit var blockingLockName: String
    lateinit var suspendLockName: String
    lateinit var blockingStaleLease: Lease
    lateinit var suspendStaleLease: Lease
    private val holderIdentity = "benchmark-conflict-holder"

    @Setup(Level.Trial)
    fun setup(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        blockingLockName = benchmark.newLockName("blocking-conflict")
        suspendLockName = benchmark.newLockName("suspend-conflict")
    }

    @Setup(Level.Invocation)
    fun setupInvocation(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        blockingStaleLease = benchmark.staleConflictLease(blockingLockName, holderIdentity)
        suspendStaleLease = benchmark.staleConflictLease(suspendLockName, holderIdentity)
    }

    @TearDown(Level.Trial)
    fun tearDown(benchmark: KubernetesBackendLeaderElectorBenchmark) {
        benchmark.cleanLease(blockingLockName)
        benchmark.cleanLease(suspendLockName)
    }
}
