package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.k8s.KubernetesLeaseGroupOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KubernetesLeaseSupportTest {

    @Test
    fun `namespace must be DNS-1123 label`() {
        KubernetesLeaseNames.validateNamespace("operator-system")

        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.validateNamespace("OperatorSystem")
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.validateNamespace("operator_system")
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.validateNamespace("x".repeat(64))
        }
    }

    @Test
    fun `options reject invalid namespace`() {
        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseOptions(namespace = "OperatorSystem")
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseGroupOptions(namespace = "operator_system")
        }
    }

    @Test
    fun `lease name must be DNS-1123 label`() {
        KubernetesLeaseNames.validateLeaseName("daily-job-1")

        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.validateLeaseName("DailyJob")
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.validateLeaseName("daily_job")
        }
    }

    @Test
    fun `group slot lease name must leave room for suffix`() {
        KubernetesLeaseNames.groupSlotLeaseName("daily-job", slot = 1, maxLeaders = 3) shouldBeEqualTo "daily-job-slot-1"

        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.groupSlotLeaseName("x".repeat(60), slot = 1, maxLeaders = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            KubernetesLeaseNames.groupSlotLeaseName("daily-job", slot = 3, maxLeaders = 3)
        }
    }

    @Test
    fun `duration converts to ceiling seconds`() {
        1.milliseconds.toLeaseDurationSeconds("leaseTime") shouldBeEqualTo 1
        999.milliseconds.toLeaseDurationSeconds("leaseTime") shouldBeEqualTo 1
        1.seconds.toLeaseDurationSeconds("leaseTime") shouldBeEqualTo 1
        1500.milliseconds.toLeaseDurationSeconds("leaseTime") shouldBeEqualTo 2
    }

    @Test
    fun `duration must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            0.seconds.toLeaseDurationSeconds("leaseTime")
        }
    }
}
