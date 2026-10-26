package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * `ExposedR2dbcLeaderGroupElectionOptions`는 Exposed R2DBC group election의 슬롯, lease, 재시도, 이력, owner 정책을 설정합니다.
 *
 * @property leaderGroupOptions 동시에 허용할 슬롯 수와 각 슬롯의 대기·lease 정책을 정하는 공통 group 옵션입니다. 기본값은
 * `LeaderGroupElectionOptions.Default`입니다.
 * @property retryStrategy 슬롯 획득을 반복할 때 사용할 재시도 지연 계산기입니다. 기본값은 50ms jitter입니다.
 * @property recordHistory `true`이면 획득·완료·실패 이력을 `LeaderLockHistoryTable`에 기록합니다. 기본값은 `false`입니다.
 * @property lockOwner 각 group lock 행과 history에 기록할 선택적 owner 식별자입니다. `null`이면 owner를 저장하지 않으며, 지정하면 최대 255자입니다.
 */
data class ExposedR2dbcLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
): Serializable {

    /** 공통 group 옵션이 허용하는 동시 leadership 슬롯 수입니다. `1` 이상으로 검증됩니다. */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        lockOwner?.let {
            it.length.requireLe(ExposedLeaderConstants.LOCK_OWNER_LENGTH, "lockOwner.length")
        }
    }

    companion object {
        /** 공통 기본 group 옵션과 50ms jitter를 사용하고 이력 기록을 끄며 owner를 저장하지 않는 R2DBC 옵션입니다. */
        @JvmField
        val Default = ExposedR2dbcLeaderGroupElectionOptions()
    }
}
