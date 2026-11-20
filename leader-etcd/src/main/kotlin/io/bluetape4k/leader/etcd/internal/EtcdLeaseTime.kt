package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.support.requirePositiveDuration
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

/**
 * `EtcdLeaseTime`는 etcd TTL 초 단위 변환과 기본 jitter 범위를 계산합니다.
 *
 * @property DefaultJitterRatio keep-alive cadence에 적용할 기본 jitter의 절대 비율 상한입니다. 값은 0.10(±10%)입니다.
 */
internal object EtcdLeaseTime {

    const val DefaultJitterRatio: Double = 0.10

    fun ttlSeconds(leaseTime: Duration, name: String = "leaseTime"): Long {
        validatePositiveFinite(leaseTime, name)

        val seconds = ceil(leaseTime.toDouble(DurationUnit.SECONDS)).toLong()
        require(seconds > 0L) { "$name must round up to at least one second. $name=$leaseTime" }
        return seconds
    }

    fun keepAliveCadence(leaseTime: Duration): Duration {
        validatePositiveFinite(leaseTime, "leaseTime")
        return (leaseTime / 3).coerceAtLeast(1.nanoseconds)
    }

    fun jitteredKeepAliveCadence(
        leaseTime: Duration,
        jitterFactor: Double,
    ): Duration {
        require(jitterFactor in -DefaultJitterRatio..DefaultJitterRatio) {
            "jitterFactor must be within +/-$DefaultJitterRatio. jitterFactor=$jitterFactor"
        }

        val baseNanos = keepAliveCadence(leaseTime).inWholeNanoseconds.coerceAtLeast(1L)
        val jitteredNanos = (baseNanos.toDouble() * (1.0 + jitterFactor))
            .roundToLong()
            .coerceAtLeast(1L)

        return jitteredNanos.nanoseconds
    }

    fun randomJitterFactor(random: Random = Random.Default): Double =
        random.nextDouble(-DefaultJitterRatio, DefaultJitterRatio)

    private fun validatePositiveFinite(duration: Duration, name: String) {
        require(duration.isFinite()) { "$name must be finite. $name=$duration" }
        duration.requirePositiveDuration(name)
    }
}
