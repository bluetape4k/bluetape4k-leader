package io.bluetape4k.leader

import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `LeaderElectionOptions`는 단일 leadership 획득과 lease 유지 정책을 설정합니다.
 *
 * @property waitTime lock 획득을 기다릴 최대 시간입니다. `0`이면 추가로 기다리지 않으며, 기본값은 5초입니다.
 * @property leaseTime 획득한 lock의 lease TTL입니다. 양수여야 하며 기본값은 60초입니다.
 * @property nodeId 이 프로세스의 leader 후보 식별자입니다. 공백은 허용되지 않으며 기본값은 `LeaderNodeId.Default`입니다.
 * @property minLeaseTime 작업이 일찍 끝나도 획득 시점부터 유지할 최소 시간입니다. `0` 이상 `leaseTime` 이하이며 기본값은 `0`입니다.
 * @property autoExtend `true`이면 작업이 실행되는 동안 watchdog이 lease를 연장합니다. 기본값은 `false`입니다.
 * @property useDbTime Exposed JDBC/R2DBC backend가 만료 시각 계산에 DB server clock을 사용할지 정합니다. 다른 backend는 이 옵션을 사용하지 않으며 기본값은 `false`입니다.
 */
data class LeaderElectionOptions(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val nodeId: String = LeaderNodeId.Default,
    val minLeaseTime: Duration = Duration.ZERO,
    val autoExtend: Boolean = false,
    val useDbTime: Boolean = false,
): Serializable {
    init {
        waitTime.requireGe(Duration.ZERO, "waitTime")
        leaseTime.requireGt(Duration.ZERO, "leaseTime")
        nodeId.requireNotBlank("nodeId")
        minLeaseTime.requireGe(Duration.ZERO, "minLeaseTime")
        minLeaseTime.requireLe(leaseTime) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        val DefaultWaitTime: Duration = 5.seconds
        val DefaultLeaseTime: Duration = 60.seconds

        /**
         * 기본 대기 시간 5초, lease TTL 60초, 자동 연장 비활성화로 구성한 기본 옵션입니다.
         */
        @JvmField
        val Default = LeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}
