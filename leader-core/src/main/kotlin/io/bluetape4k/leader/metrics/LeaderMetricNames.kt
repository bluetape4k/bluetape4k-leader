package io.bluetape4k.leader.metrics

/**
 * leader election Micrometer 계측에서 사용하는 metric 이름을 모읍니다.
 */
object LeaderMetricNames {

    /**
     * leader ID 해결에 실패한 횟수를 나타내는 `leader.aop.leader_id.resolution_failed` counter 이름입니다.
     */
    const val METRIC_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"
}
