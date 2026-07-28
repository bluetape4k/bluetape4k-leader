package io.bluetape4k.leader.metrics

/**
 * `LeaderMetricNames` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object LeaderMetricNames {

    /**
     * `METRIC_LEADER_ID_RESOLUTION_FAILED` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    const val METRIC_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"
}
