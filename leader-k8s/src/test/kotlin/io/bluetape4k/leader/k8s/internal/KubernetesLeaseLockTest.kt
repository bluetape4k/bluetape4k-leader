package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseLockTest {

    @Test
    fun `acquire rebuilds lease annotations without mutating current lease`() {
        val namespace = "leader-test"
        val lockName = "orders"
        val now = Instant.parse("2026-05-22T12:00:00Z")
        val current = lease(
            namespace = namespace,
            lockName = lockName,
            holder = "previous-owner",
            renewTime = now.minusSeconds(30),
            annotations = linkedMapOf("existing" to "kept"),
        )
        val originalAnnotations = LinkedHashMap(current.metadata.annotations)
        val updatedSlot = slot<Lease>()
        val client = mockClientReturningCurrentLease(namespace, lockName, current, updatedSlot)
        val lock = KubernetesLeaseLock(
            client = client,
            namespace = namespace,
            lockName = lockName,
            ownerToken = "owner-token",
            auditLeaderId = "partition-a",
            nodeId = "node-a",
            retryDelay = 10.milliseconds,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val acquired = lock.tryLock(waitTime = 0.seconds, leaseTime = 10.seconds)

        acquired shouldBeEqualTo true
        current.metadata.annotations shouldBeEqualTo originalAnnotations
        updatedSlot.captured.metadata.annotations shouldBeEqualTo
            linkedMapOf(
                "existing" to "kept",
                KubernetesLeaseAnnotations.AuditLeaderId to "partition-a",
                KubernetesLeaseAnnotations.ManagedBy to KubernetesLeaseAnnotations.ManagedByValue,
                KubernetesLeaseAnnotations.NodeId to "node-a",
            )
        verify(exactly = 1) { client.resource(any<Lease>()) }
    }

    private fun mockClientReturningCurrentLease(
        namespace: String,
        lockName: String,
        current: Lease,
        updatedSlot: io.mockk.CapturingSlot<Lease>,
    ): KubernetesClient {
        val client = mockk<KubernetesClient>()
        val leases = mockk<MixedOperation<Lease, LeaseList, Resource<Lease>>>()
        val namespaced = mockk<NonNamespaceOperation<Lease, LeaseList, Resource<Lease>>>()
        val named = mockk<Resource<Lease>>()
        val updateResource = mockk<NamespaceableResource<Lease>>()

        every { client.leases() } returns leases
        every { leases.inNamespace(namespace) } returns namespaced
        every { namespaced.withName(lockName) } returns named
        every { named.get() } returns current
        every { client.resource(capture(updatedSlot)) } returns updateResource
        every { updateResource.update() } answers { updatedSlot.captured }

        return client
    }

    private fun lease(
        namespace: String,
        lockName: String,
        holder: String,
        renewTime: Instant,
        annotations: Map<String, String>,
    ): Lease =
        LeaseBuilder()
            .withNewMetadata()
            .withNamespace(namespace)
            .withName(lockName)
            .withResourceVersion("1")
            .withAnnotations<String, String>(annotations)
            .endMetadata()
            .withNewSpec()
            .withHolderIdentity(holder)
            .withLeaseDurationSeconds(5)
            .withAcquireTime(ZonedDateTime.ofInstant(renewTime.minusSeconds(10), ZoneOffset.UTC))
            .withRenewTime(ZonedDateTime.ofInstant(renewTime, ZoneOffset.UTC))
            .withLeaseTransitions(0)
            .endSpec()
            .build()
}
