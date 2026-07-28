package io.bluetape4k.leader.etcd.internal

import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

/**
 * `EtcdLeaseTime`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property DefaultJitterRatio etcd backend 호출과 상태 계산에 사용하는 속성입니다.
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
        require(duration > Duration.ZERO) { "$name must be positive. $name=$duration" }
    }
}
