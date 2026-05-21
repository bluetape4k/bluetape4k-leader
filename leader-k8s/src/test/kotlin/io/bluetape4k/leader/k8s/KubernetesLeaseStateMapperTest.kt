package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseAnnotations
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class KubernetesLeaseStateMapperTest {

    private val now = Instant.parse("2026-05-21T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `missing lease maps to empty state`() {
        val state = KubernetesLeaseStateMapper.map("daily-job", null, clock)

        state.isEmpty.shouldBeTrue()
        state.leader.shouldBeNull()
    }

    @Test
    fun `blank holder maps to empty state`() {
        val state = KubernetesLeaseStateMapper.map("daily-job", lease(holder = ""), clock)

        state.isEmpty.shouldBeTrue()
    }

    @Test
    fun `expired lease maps to empty state`() {
        val state = KubernetesLeaseStateMapper.map(
            "daily-job",
            lease(renewTime = now.minusSeconds(31), durationSeconds = 30),
            clock,
        )

        state.isEmpty.shouldBeTrue()
    }

    @Test
    fun `active lease maps annotations to leader lease`() {
        val state = KubernetesLeaseStateMapper.map(
            "daily-job",
            lease(
                holder = "token-a",
                acquireTime = now.minusSeconds(10),
                renewTime = now.minusSeconds(3),
                durationSeconds = 30,
                annotations = mapOf(
                    KubernetesLeaseAnnotations.AuditLeaderId to "audit-a",
                    KubernetesLeaseAnnotations.NodeId to "node-a",
                ),
            ),
            clock,
        )

        state.isOccupied.shouldBeTrue()
        state.leader?.auditLeaderId shouldBeEqualTo "audit-a"
        state.leader?.nodeId shouldBeEqualTo "node-a"
        state.leader?.electedAt shouldBeEqualTo now.minusSeconds(10)
        state.leader?.leaseUntil shouldBeEqualTo now.plusSeconds(27)
    }

    @Test
    fun `active lease falls back to holder as audit identity`() {
        val state = KubernetesLeaseStateMapper.map("daily-job", lease(holder = "token-a"), clock)

        state.isEmpty.shouldBeFalse()
        state.leader?.auditLeaderId shouldBeEqualTo "token-a"
    }

    private fun lease(
        holder: String? = "token-a",
        acquireTime: Instant = now.minusSeconds(10),
        renewTime: Instant = now.minusSeconds(3),
        durationSeconds: Int = 30,
        annotations: Map<String, String> = emptyMap(),
    ): Lease =
        LeaseBuilder()
            .withNewMetadata()
            .withName("daily-job")
            .withNamespace("default")
            .withAnnotations<String, String>(annotations)
            .endMetadata()
            .withNewSpec()
            .withHolderIdentity(holder)
            .withAcquireTime(ZonedDateTime.ofInstant(acquireTime, ZoneOffset.UTC))
            .withRenewTime(ZonedDateTime.ofInstant(renewTime, ZoneOffset.UTC))
            .withLeaseDurationSeconds(durationSeconds)
            .endSpec()
            .build()
}
