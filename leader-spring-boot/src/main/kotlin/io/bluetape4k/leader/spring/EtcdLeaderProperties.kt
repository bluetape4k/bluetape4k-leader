package io.bluetape4k.leader.spring

import java.io.Serializable

/**
 * Spring Boot의 etcd leader backend가 lease key를 만드는 설정입니다.
 *
 * @property keyPrefix etcd lock key 앞에 붙이는 prefix입니다. 기본값은 `/bluetape4k/leader`입니다.
 */
data class EtcdLeaderProperties(
    val keyPrefix: String = DefaultKeyPrefix,
): Serializable {
    companion object {
        const val DefaultKeyPrefix: String = "/bluetape4k/leader"

        private const val serialVersionUID: Long = 1L
    }
}
