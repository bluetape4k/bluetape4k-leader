package io.bluetape4k.leader.examples.k8slease

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class K8sLeaseDurationTest {
    @ParameterizedTest
    @CsvSource("0,1,1", "0,500000000,1", "1,0,1", "1,1,2", "1,500000000,2", "2147483647,0,2147483647")
    fun `create와 update는 같은 양수 올림 초를 사용한다`(seconds: Long, nanos: Long, expected: Int) {
        val client = mockk<KubernetesClient>(relaxed = true)
        val created = slot<Lease>()
        val updated = slot<Lease>()
        every { client.leases().inNamespace("default").withName("duration").get() } returns null andThen
            LeaseBuilder().withNewMetadata().withName("duration").endMetadata()
                .withNewSpec().withHolderIdentity("owner").endSpec().build()
        every { client.leases().inNamespace("default").resource(capture(created)).create() } answers { created.captured }
        every { client.resource(capture(updated)).update() } answers { updated.captured }
        val example = K8sLeaseLeaderElectionExample(client, leaseDuration = Duration.ofSeconds(seconds, nanos))
        example.tryAcquire("duration", "owner")
        example.tryAcquire("duration", "owner")
        created.captured.spec.leaseDurationSeconds shouldBeEqualTo expected
        updated.captured.spec.leaseDurationSeconds shouldBeEqualTo expected
    }

    @ParameterizedTest
    @CsvSource("0,0", "-1,0", "2147483647,1", "2147483648,0", "9223372036854775807,0")
    fun `범위 밖 기간은 client 호출 전에 거부한다`(seconds: Long, nanos: Long) {
        val client = mockk<KubernetesClient>()
        assertFailsWith<IllegalArgumentException> {
            K8sLeaseLeaderElectionExample(client, leaseDuration = Duration.ofSeconds(seconds, nanos))
        }
        verify { client wasNot Called }
    }

    @Test
    fun `기존 Lease에 기간이 없어도 같은 올림 정책으로 만료를 판단한다`() {
        val now = Instant.parse("2026-09-08T00:00:00Z")
        val client = mockk<KubernetesClient>(relaxed = true)
        val current = LeaseBuilder().withNewMetadata().withName("duration").endMetadata()
            .withNewSpec().withHolderIdentity("other")
            .withRenewTime(ZonedDateTime.ofInstant(now.minusMillis(750), ZoneOffset.UTC))
            .endSpec().build()
        every { client.leases().inNamespace("default").withName("duration").get() } returns current
        val example = K8sLeaseLeaderElectionExample(
            client, leaseDuration = Duration.ofMillis(500), clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        example.tryAcquire("duration", "owner").outcome shouldBeEqualTo LeaseOutcome.CONFLICT
    }
}
