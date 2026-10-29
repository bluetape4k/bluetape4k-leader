package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.support.requirePositiveDuration
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MongoLeaderGroupElectionOptions`는 MongoDB group election의 공통 슬롯 정책과 재시도 간격을 설정합니다.
 *
 * @property leaderGroupOptions 슬롯 수, 대기 시간, lease TTL을 정하는 공통 group 옵션입니다. 기본값은
 * `LeaderGroupElectionOptions.Default`입니다.
 * @property retryDelay `MongoLock`과 `MongoSuspendLock`의 재시도 사이 지연을 계산할 때 사용하는 값입니다. 양수여야 하며 기본값은 50밀리초입니다. 지연은
 * 밀리초 단위 jitter로 정하고, 설정이 2밀리초보다 짧으면 1밀리초를 사용합니다.
 */
data class MongoLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
): Serializable {

    /**
     * 공통 group 옵션에 지정한 동시 leadership 슬롯 수입니다. 값은 `leaderGroupOptions.maxLeaders`와 같습니다.
     */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        retryDelay.requirePositiveDuration("retryDelay")
    }

    companion object {
        /**
         * 공통 기본 group 옵션과 50밀리초 재시도 간격을 사용하는 MongoDB 기본 옵션입니다.
         */
        @JvmField
        val Default = MongoLeaderGroupElectionOptions()

        /** Kotlin의 no-arg constructor 추가 전 공개된 직렬화 UID를 유지합니다. */
        private const val serialVersionUID: Long = -6752496615359943498L
    }
}
