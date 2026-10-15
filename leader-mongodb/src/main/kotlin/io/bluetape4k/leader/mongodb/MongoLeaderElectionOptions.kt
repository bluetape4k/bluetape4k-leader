package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireGt
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MongoLeaderElectionOptions`는 MongoDB 단일 선출에 전달할 core 정책과 lock 경합 재시도 상한을 묶는 직렬화 가능한 옵션 모델입니다.
 *
 * @property leaderOptions MongoDB 단일 lock에 전달할 core 대기·lease·node·최소 lease·auto-extend 정책입니다.
 * @property retryDelay lock 경합 재시도 사이의 양의 지연 상한입니다. 기본값은 `50.milliseconds`이며 실제 sleep은 이 범위의 jitter입니다.
 */
data class MongoLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryDelay: Duration = 50.milliseconds,
): Serializable {
    init {
        retryDelay.requireGt(Duration.ZERO, "retryDelay")
    }

    companion object {
        /** core 기본 선출 정책과 50ms retry 상한을 사용하는 MongoDB 옵션입니다. */
        @JvmField
        val Default = MongoLeaderElectionOptions()

        /** Kotlin의 no-arg constructor 추가 전 공개된 직렬화 UID를 유지합니다. */
        private const val serialVersionUID: Long = 8621706450900702701L
    }
}
