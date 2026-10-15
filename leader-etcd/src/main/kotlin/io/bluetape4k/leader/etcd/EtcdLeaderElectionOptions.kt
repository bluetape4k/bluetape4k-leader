package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.etcd.internal.EtcdLeaderPaths
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `EtcdLeaderElectionOptions`는 etcd backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions lease·대기·최소 lease·자동 연장과 owner를 포함한 공통 단일 선거 설정입니다.
 * @property keyPrefix etcd 키의 루트 경로입니다. `/`로 시작하고 끝의 `/`는 정규화되며 기본값은 `/bluetape4k/leader`입니다.
 * @property retryDelay 경합 또는 transient 오류 뒤 재시도까지의 지연입니다. 양수 duration이어야 하며 기본값은 50ms입니다.
 */
data class EtcdLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
    val retryDelay: Duration = 50.milliseconds,
): Serializable {

    init {
        EtcdLeaderPaths(keyPrefix)
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /** 공통 기본 선거 설정과 `/bluetape4k/leader` 경로, 50ms retry delay를 사용하는 인스턴스입니다. */
        @JvmField
        val Default = EtcdLeaderElectionOptions()

        private const val serialVersionUID = 1L
    }
}
