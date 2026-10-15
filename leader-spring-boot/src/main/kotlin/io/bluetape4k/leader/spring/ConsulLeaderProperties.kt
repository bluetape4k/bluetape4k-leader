package io.bluetape4k.leader.spring

import java.io.Serializable
import java.time.Duration

/**
 * Spring Boot의 Consul leader backend가 lock key와 session 이름을 만드는 설정입니다.
 *
 * @property keyPrefix Consul KV lock key 앞에 붙이는 prefix입니다. 기본값은 `bluetape4k/leader`입니다.
 * @property sessionNamePrefix Consul session 이름 앞에 붙이는 prefix입니다. 기본값은 `bluetape4k-leader`입니다.
 * @property lockDelay Consul lock release 후 재획득을 지연하는 시간입니다. 기본값은 `Duration.ZERO`입니다.
 */
data class ConsulLeaderProperties(
    val keyPrefix: String = DefaultKeyPrefix,
    val sessionNamePrefix: String = DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
): Serializable {
    companion object {
        const val DefaultKeyPrefix: String = "bluetape4k/leader"
        const val DefaultSessionNamePrefix: String = "bluetape4k-leader"

        private const val serialVersionUID: Long = 1L
    }
}
