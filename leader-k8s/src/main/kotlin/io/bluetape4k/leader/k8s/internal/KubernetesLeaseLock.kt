package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.k8s.KubernetesLeaseStateMapper
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal class KubernetesLeaseLock(
    private val client: KubernetesClient,
    private val namespace: String,
    val lockName: String,
    val ownerToken: String,
    private val auditLeaderId: String,
    private val nodeId: String,
    private val retryDelay: Duration,
    private val clock: Clock,
) {
    companion object : KLogging() {
        private const val CONFLICT = 409

        fun newOwnerToken(): String = "b4k-${Base58.randomString(22)}"
    }

    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean {
        KubernetesLeaseNames.validateLeaseName(lockName)
        val startedAt = System.nanoTime()

        do {
            when (tryAcquireOnce(leaseTime)) {
                AcquireResult.Acquired -> {
                    log.debug { "Kubernetes Lease acquired. lockName=$lockName, ownerToken=$ownerToken" }
                    return true
                }
                AcquireResult.Contended -> Unit
            }

            val remaining = remainingWait(waitTime, startedAt)
            if (remaining <= Duration.ZERO) {
                log.debug { "Kubernetes Lease acquire skipped by contention. lockName=$lockName" }
                return false
            }
            sleepBeforeRetry(remaining)
        } while (true)
    }

    fun unlock(
        minLeaseTime: Duration = Duration.ZERO,
        acquiredAtNanos: Long = System.nanoTime(),
    ): Boolean {
        val current = lease() ?: return false
        if (current.spec?.holderIdentity != ownerToken) {
            log.warn { "Kubernetes Lease release skipped by owner mismatch. lockName=$lockName" }
            return false
        }

        val now = now()
        val remaining = remainingMinLeaseTime(acquiredAtNanos, minLeaseTime)
        val specBuilder = LeaseSpecBuilder(current.spec)
            .withRenewTime(now)
        if (remaining > Duration.ZERO) {
            specBuilder.withHolderIdentity(ownerToken)
                .withLeaseDurationSeconds(remaining.toLeaseDurationSeconds("remainingMinLeaseTime"))
        } else {
            specBuilder.withHolderIdentity(null)
        }

        return updateLease(current, specBuilder.build())
    }

    fun extendDetailed(leaseTime: Duration): ExtendOutcome {
        val current = lease() ?: return ExtendOutcome.NotHeld
        if (current.spec?.holderIdentity != ownerToken || isExpired(current)) {
            return ExtendOutcome.NotHeld
        }

        val now = now()
        val seconds = leaseTime.toLeaseDurationSeconds("leaseTime")
        val updatedSpec = LeaseSpecBuilder(current.spec)
            .withHolderIdentity(ownerToken)
            .withLeaseDurationSeconds(seconds)
            .withRenewTime(now)
            .build()

        return try {
            if (updateLease(current, updatedSpec)) {
                ExtendOutcome.Extended(now.toInstant().plusSeconds(seconds.toLong()))
            } else {
                ExtendOutcome.NotHeld
            }
        } catch (e: Exception) {
            ExtendOutcome.BackendError(e)
        }
    }

    fun isHeldByCurrentInstance(): Boolean {
        val current = lease() ?: return false
        return current.spec?.holderIdentity == ownerToken && !isExpired(current)
    }

    fun state(): LeaderState =
        KubernetesLeaseStateMapper.map(lockName, lease(), clock)

    fun delete() {
        client.leases().inNamespace(namespace).withName(lockName).delete()
    }

    private fun tryAcquireOnce(leaseTime: Duration): AcquireResult {
        val now = now()
        val current = lease()
        if (current == null) {
            return createLease(now, leaseTime)
        }

        if (!canAcquire(current)) {
            return AcquireResult.Contended
        }

        val currentHolder = current.spec?.holderIdentity
        val transitionIncrement = if (!currentHolder.isNullOrBlank() && currentHolder != ownerToken) 1 else 0
        val currentTransitions = current.spec?.leaseTransitions ?: 0
        val acquiredAt = if (!currentHolder.isNullOrBlank() && currentHolder != ownerToken) {
            now
        } else {
            current.spec?.acquireTime ?: now
        }
        val updatedSpec = LeaseSpecBuilder(current.spec)
            .withHolderIdentity(ownerToken)
            .withLeaseDurationSeconds(leaseTime.toLeaseDurationSeconds("leaseTime"))
            .withAcquireTime(acquiredAt)
            .withRenewTime(now)
            .withLeaseTransitions(currentTransitions + transitionIncrement)
            .build()

        return if (updateLease(current, updatedSpec)) AcquireResult.Acquired else AcquireResult.Contended
    }

    private fun createLease(now: ZonedDateTime, leaseTime: Duration): AcquireResult {
        val lease = LeaseBuilder()
            .withNewMetadata()
            .withName(lockName)
            .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
            .withHolderIdentity(ownerToken)
            .withLeaseDurationSeconds(leaseTime.toLeaseDurationSeconds("leaseTime"))
            .withAcquireTime(now)
            .withRenewTime(now)
            .withLeaseTransitions(0)
            .endSpec()
            .build()
            .withBluetape4kAnnotations()

        return try {
            client.leases().inNamespace(namespace).resource(lease).create()
            AcquireResult.Acquired
        } catch (e: KubernetesClientException) {
            if (e.code == CONFLICT) AcquireResult.Contended else throw e
        }
    }

    private fun updateLease(current: Lease, updatedSpec: io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpec): Boolean {
        val updated = LeaseBuilder(current)
            .withSpec(updatedSpec)
            .build()
            .withBluetape4kAnnotations()

        return try {
            client.resource(updated).update()
            true
        } catch (e: KubernetesClientException) {
            if (e.code == CONFLICT) {
                false
            } else {
                throw e
            }
        }
    }

    private fun Lease.withBluetape4kAnnotations(): Lease {
        val annotations = LinkedHashMap<String, String>(metadata?.annotations.orEmpty())
        annotations[KubernetesLeaseAnnotations.AuditLeaderId] = auditLeaderId
        annotations[KubernetesLeaseAnnotations.ManagedBy] = KubernetesLeaseAnnotations.ManagedByValue
        annotations[KubernetesLeaseAnnotations.NodeId] = nodeId
        val updatedMetadata = ObjectMetaBuilder(metadata ?: ObjectMeta())
            .withAnnotations<String, String>(annotations)
            .build()
        return LeaseBuilder(this)
            .withMetadata(updatedMetadata)
            .build()
    }

    private fun canAcquire(lease: Lease): Boolean {
        val holder = lease.spec?.holderIdentity
        return holder.isNullOrBlank() || holder == ownerToken || isExpired(lease)
    }

    private fun isExpired(lease: Lease): Boolean {
        val leaseUntil = KubernetesLeaseStateMapper.leaseUntil(lease) ?: return true
        return !leaseUntil.isAfter(clock.instant())
    }

    private fun lease(): Lease? =
        client.leases().inNamespace(namespace).withName(lockName).get()

    private fun now(): ZonedDateTime =
        ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun sleepBeforeRetry(remaining: Duration) {
        val retryMillis = retryDelay.inWholeMilliseconds.coerceAtLeast(2L)
        val jitter = ThreadLocalRandom.current().nextLong(1L, retryMillis)
        val delayMillis = min(jitter, remaining.inWholeMilliseconds.coerceAtLeast(1L))
        Thread.sleep(delayMillis)
    }

    private fun remainingWait(waitTime: Duration, startedAtNanos: Long): Duration {
        if (waitTime <= Duration.ZERO) {
            return Duration.ZERO
        }
        val elapsed = (System.nanoTime() - startedAtNanos).nanoseconds
        val remaining = waitTime - elapsed
        return if (remaining > Duration.ZERO) remaining else Duration.ZERO
    }

    private enum class AcquireResult {
        Acquired,
        Contended,
    }
}
