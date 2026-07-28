package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `MongoLeaderGroupElectionOptions`는 MongoDB leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderGroupOptions MongoDB backend 계약에서 `leaderGroupOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay MongoDB backend 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class MongoLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {

    /**
     * `maxLeaders` 값은 MongoDB backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        maxLeaders.requirePositiveNumber("maxLeaders")
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * `Default` 값은 MongoDB backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default = MongoLeaderGroupElectionOptions()
    }
}
