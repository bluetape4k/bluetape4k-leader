package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.support.requireNotBlank

/**
 * metrics event에 연결된 leader ID가 있는지 전달합니다.
 *
 */
sealed interface LeaderAopMetricsContext {

    /**
     * metrics 실행에서 leader ID를 확인할 수 없음을 나타냅니다.
     *
     */
    data object Unknown: LeaderAopMetricsContext

    /**
     * 확인된 leader ID와 그 값을 얻은 경로를 나타냅니다.
     *
     * @property leaderId metrics event에 기록할 leader ID입니다. 공백 문자열은 허용되지 않습니다.
     * @property leaderIdSource ID가 literal, SpEL, property 또는 자동 식별 중 어떤 경로에서 왔는지 나타냅니다.
     */
    data class Identified(
        val leaderId: String,
        val leaderIdSource: LeaderIdSource,
    ): LeaderAopMetricsContext {
        init {
            leaderId.requireNotBlank("leaderId")
        }
    }

    companion object {
        /** ID가 확인되지 않은 `Unknown` 문맥과 같은 sentinel입니다. */
        @JvmField
        val Empty: LeaderAopMetricsContext = Unknown
    }
}
