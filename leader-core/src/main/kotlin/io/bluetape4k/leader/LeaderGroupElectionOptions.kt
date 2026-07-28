package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `LeaderGroupElectionOptions`는 group leader election 옵션입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
 * @property waitTime leader lock 획득을 기다리는 최대 시간입니다.
 * @property leaseTime leadership을 보유할 수 있는 lease TTL입니다.
 * @property nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
 * @property minLeaseTime 작업이 빨리 끝나더라도 lease를 최소로 유지할 시간입니다.
 */
data class LeaderGroupElectionOptions(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val nodeId: String = LeaderNodeId.Default,
    val minLeaseTime: Duration = Duration.ZERO,
): Serializable {

    init {
        maxLeaders.requireGe(1, "maxLeaders")
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
        nodeId.requireNotBlank("nodeId")
        minLeaseTime.requireGe(Duration.ZERO, "minLeaseTime")
        require(minLeaseTime <= leaseTime) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * `Default` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        @JvmField
        val Default = LeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}
