package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLeaderPaths
import io.bluetape4k.support.requirePositiveDuration
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `EtcdLeaderGroupElectionOptions`는 etcd backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderGroupOptions maxLeaders·lease·대기·최소 lease를 포함한 공통 그룹 선거 설정입니다.
 * @property keyPrefix etcd 그룹 키의 루트 경로이며 `/`로 시작해야 합니다. 기본값은 `/bluetape4k/leader`입니다.
 * @property retryDelay 경합 또는 transient 오류 뒤 재시도까지의 양수 지연이며 기본값은 50ms입니다.
 */
data class EtcdLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
    val retryDelay: Duration = 50.milliseconds,
): Serializable {

    /** lock name별로 동시에 허용되는 활성 슬롯의 상한입니다. 공통 그룹 옵션에서 가져옵니다. */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        EtcdLeaderPaths(keyPrefix)
        retryDelay.requirePositiveDuration("retryDelay")
    }

    companion object {
        /** 공통 기본 그룹 설정과 `/bluetape4k/leader` 경로, 50ms retry delay를 사용하는 인스턴스입니다. */
        @JvmField
        val Default = EtcdLeaderGroupElectionOptions()

        private const val serialVersionUID = 1L
    }
}
