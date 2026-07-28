package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `MongoLeaderElectionOptions`는 MongoDB leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property leaderOptions MongoDB backend 계약에서 `leaderOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property retryDelay MongoDB backend 계약에서 `retryDelay` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class MongoLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
) : Serializable {
    init {
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /**
         * `Default` 값은 MongoDB backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default = MongoLeaderElectionOptions()
    }
}
