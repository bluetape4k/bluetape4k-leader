package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLeaderPaths
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `EtcdLeaderGroupElectionOptions`는 etcd backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderGroupOptions etcd backend 계약에서 `leaderGroupOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property keyPrefix etcd backend 계약에서 `keyPrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay etcd backend 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class EtcdLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    /**
     * `maxLeaders` 값은 etcd backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        EtcdLeaderPaths(keyPrefix)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * `Default` 값은 etcd backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default = EtcdLeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}
