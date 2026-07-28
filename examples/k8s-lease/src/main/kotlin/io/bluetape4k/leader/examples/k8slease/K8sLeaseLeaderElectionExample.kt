package io.bluetape4k.leader.examples.k8slease

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime

/**
 * `K8sLeaseLeaderElectionExample`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property client example workflow 계약에서 사용하는 속성입니다.
 * @property namespace example workflow 계약에서 사용하는 속성입니다.
 * @property leaseDuration example workflow 계약에서 사용하는 속성입니다.
 * @property clock example workflow 계약에서 사용하는 속성입니다.
 */
class K8sLeaseLeaderElectionExample(
    private val client: KubernetesClient,
    private val namespace: String = "default",
    private val leaseDuration: Duration = Duration.ofSeconds(30),
    private val clock: Clock = Clock.systemUTC(),
) {

    init {
        validateKubernetesNamespace(namespace)
        require(!leaseDuration.isNegative && !leaseDuration.isZero) {
            "leaseDuration must be positive. leaseDuration=$leaseDuration"
        }
    }

    companion object: KLogging() {
        private const val CONFLICT_STATUS = 409
        private val DNS_1123_LABEL = Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")

        @JvmStatic
        fun main(args: Array<String>) {
            println("Run `./gradlew :examples:k8s-lease:k8sTest` to execute the K3s-backed Lease example.")
        }
    }

    fun tryAcquire(leaseName: String, holderIdentity: String): LeaseAttempt {
        validateLeaseName(leaseName)
        holderIdentity.requireNotBlank("holderIdentity")

        val now = now()
        val current = lease(leaseName)
        if (current == null) {
            return createLease(leaseName, holderIdentity, now)
        }

        if (!canAcquire(current, holderIdentity, now)) {
            return LeaseAttempt(
                outcome = LeaseOutcome.CONFLICT,
                holderIdentity = current.spec?.holderIdentity,
                leaseName = leaseName,
            )
        }

        val updated = LeaseBuilder(current)
            .withSpec(
                LeaseSpecBuilder(current.spec)
                    .withHolderIdentity(holderIdentity)
                    .withLeaseDurationSeconds(leaseDuration.seconds.toInt())
                    .withRenewTime(now)
                    .withLeaseTransitions((current.spec?.leaseTransitions ?: 0) + transitionIncrement(current, holderIdentity))
                    .build()
            )
            .build()

        client.resource(updated).update()
        log.info { "Lease acquired. leaseName=$leaseName, holderIdentity=$holderIdentity" }
        return LeaseAttempt(LeaseOutcome.ACQUIRED, holderIdentity, leaseName)
    }

    fun release(leaseName: String, holderIdentity: String): Boolean {
        validateLeaseName(leaseName)
        holderIdentity.requireNotBlank("holderIdentity")

        val current = lease(leaseName) ?: return false
        if (current.spec?.holderIdentity != holderIdentity) {
            return false
        }

        val released = LeaseBuilder(current)
            .withSpec(
                LeaseSpecBuilder(current.spec)
                    .withHolderIdentity(null)
                    .withRenewTime(now())
                    .build()
            )
            .build()

        client.resource(released).update()
        log.info { "Lease released. leaseName=$leaseName, holderIdentity=$holderIdentity" }
        return true
    }

    fun delete(leaseName: String) {
        validateLeaseName(leaseName)
        client.leases().inNamespace(namespace).withName(leaseName).delete()
    }

    private fun createLease(
        leaseName: String,
        holderIdentity: String,
        now: ZonedDateTime,
    ): LeaseAttempt {
        val lease = LeaseBuilder()
            .withNewMetadata()
            .withName(leaseName)
            .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
            .withHolderIdentity(holderIdentity)
            .withLeaseDurationSeconds(leaseDuration.seconds.toInt())
            .withAcquireTime(now)
            .withRenewTime(now)
            .withLeaseTransitions(0)
            .endSpec()
            .build()

        return try {
            client.leases().inNamespace(namespace).resource(lease).create()
            log.info { "Lease created. leaseName=$leaseName, holderIdentity=$holderIdentity" }
            LeaseAttempt(LeaseOutcome.ACQUIRED, holderIdentity, leaseName)
        } catch (e: KubernetesClientException) {
            if (e.code != CONFLICT_STATUS) {
                throw e
            }
            LeaseAttempt(LeaseOutcome.CONFLICT, holderIdentity = lease(leaseName)?.spec?.holderIdentity, leaseName = leaseName)
        }
    }

    private fun lease(leaseName: String): Lease? =
        client.leases().inNamespace(namespace).withName(leaseName).get()

    private fun canAcquire(
        lease: Lease,
        holderIdentity: String,
        now: ZonedDateTime,
    ): Boolean {
        val spec = lease.spec ?: return true
        val currentHolder = spec.holderIdentity
        if (currentHolder.isNullOrBlank() || currentHolder == holderIdentity) {
            return true
        }

        val renewedAt = spec.renewTime ?: spec.acquireTime ?: return true
        val leaseSeconds = spec.leaseDurationSeconds ?: leaseDuration.seconds.toInt()
        return renewedAt.toInstant().plusSeconds(leaseSeconds.toLong()).isBefore(now.toInstant())
    }

    private fun transitionIncrement(
        lease: Lease,
        holderIdentity: String,
    ): Int {
        val currentHolder = lease.spec?.holderIdentity
        return if (!currentHolder.isNullOrBlank() && currentHolder != holderIdentity) 1 else 0
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    private fun validateKubernetesNamespace(namespace: String) {
        namespace.requireNotBlank("namespace")
        require(namespace.length <= 63) {
            "namespace must be at most 63 characters for Kubernetes namespace name. namespace=$namespace"
        }
        require(DNS_1123_LABEL.matches(namespace)) {
            "namespace must be a DNS-1123 label for Kubernetes namespace name. namespace=$namespace"
        }
    }

    private fun validateLeaseName(leaseName: String) {
        leaseName.requireNotBlank("leaseName")
        require(leaseName.length <= 63) {
            "leaseName must be at most 63 characters for Kubernetes Lease name. leaseName=$leaseName"
        }
        require(DNS_1123_LABEL.matches(leaseName)) {
            "leaseName must be a DNS-1123 label for Kubernetes Lease name. leaseName=$leaseName"
        }
    }
}

enum class LeaseOutcome {
    ACQUIRED,
    CONFLICT,
}

/**
 * `LeaseAttempt`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property outcome example workflow 계약에서 `outcome` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property holderIdentity example workflow 계약에서 `holderIdentity` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseName example workflow 계약에서 `leaseName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaseAttempt(
    val outcome: LeaseOutcome,
    val holderIdentity: String?,
    val leaseName: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
