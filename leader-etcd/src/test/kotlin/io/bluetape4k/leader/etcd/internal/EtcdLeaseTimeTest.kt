package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaseTimeTest {

    @Test
    fun `ttl seconds rounds positive durations up`() {
        EtcdLeaseTime.ttlSeconds(1.nanoseconds) shouldBeEqualTo 1L
        EtcdLeaseTime.ttlSeconds(999.milliseconds) shouldBeEqualTo 1L
        EtcdLeaseTime.ttlSeconds(1.seconds) shouldBeEqualTo 1L
        EtcdLeaseTime.ttlSeconds(1_001.milliseconds) shouldBeEqualTo 2L
    }

    @Test
    fun `ttl seconds rejects non positive and infinite durations`() {
        assertFailsWith<IllegalArgumentException> { EtcdLeaseTime.ttlSeconds(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { EtcdLeaseTime.ttlSeconds((-1).seconds) }
        assertFailsWith<IllegalArgumentException> { EtcdLeaseTime.ttlSeconds(Duration.INFINITE) }
    }

    @Test
    fun `keepalive cadence is one third of lease time`() {
        EtcdLeaseTime.keepAliveCadence(60.seconds) shouldBeEqualTo 20.seconds
        EtcdLeaseTime.keepAliveCadence(1.nanoseconds) shouldBeEqualTo 1.nanoseconds
    }

    @Test
    fun `jittered cadence stays inside plus minus ten percent band`() {
        val base = 30.seconds

        EtcdLeaseTime.jitteredKeepAliveCadence(base, -0.10) shouldBeEqualTo 9.seconds
        EtcdLeaseTime.jitteredKeepAliveCadence(base, 0.0) shouldBeEqualTo 10.seconds
        EtcdLeaseTime.jitteredKeepAliveCadence(base, 0.10) shouldBeEqualTo 11.seconds
    }

    @Test
    fun `random jitter factor is deterministic with injected random and bounded`() {
        val random = Random(42)

        repeat(100) {
            val factor = EtcdLeaseTime.randomJitterFactor(random)
            factor shouldBeGreaterOrEqualTo -0.10
            factor shouldBeLessOrEqualTo 0.10
        }
    }

    @Test
    fun `jitter factor outside accepted band is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EtcdLeaseTime.jitteredKeepAliveCadence(30.seconds, -0.1001)
        }
        assertFailsWith<IllegalArgumentException> {
            EtcdLeaseTime.jitteredKeepAliveCadence(30.seconds, 0.1001)
        }
    }
}
