package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KubernetesLeaseSupportTest {

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
