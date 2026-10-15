package io.bluetape4k.leader.spring.properties

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderElectionOptions
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * annotation에 지정하지 않은 공통 leader election 대기 시간과 lease TTL의 Spring Boot 기본값입니다.
 *
 * @property waitTime lock 획득을 기다릴 최대 시간입니다. 기본값은 5초입니다.
 * @property leaseTime 획득한 lock의 lease TTL입니다. 기본값은 60초입니다.
 * @property group group election 기본 설정을 담습니다. 기본값은 `LeaderGroupProperties()`입니다.
 */
data class LeaderElectionProperties(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val group: LeaderGroupProperties = LeaderGroupProperties(),
) {
    companion object {
        val DefaultWaitTime: Duration = 5.seconds()
        val DefaultLeaseTime: Duration = 60.seconds()
    }

    fun toOptions(): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = waitTime.toKotlinDuration(),
            leaseTime = leaseTime.toKotlinDuration(),
        )
}
