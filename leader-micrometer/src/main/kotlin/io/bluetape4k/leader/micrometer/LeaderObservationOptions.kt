package io.bluetape4k.leader.micrometer

import java.io.Serializable

/**
 * Micrometer `Observation`에 leader election 정보를 어느 범위까지 기록할지 정합니다.
 *
 * @property includeLockName `true`이면 observation의 high-cardinality `lock.name`에 lock 이름을 추가합니다. 기본값은 `false`입니다.
 * @property includeLeaderId `true`이면 식별 가능한 context의 leader ID와 출처를 observation에 추가합니다. 기본값은 `false`입니다.
 * @property includeExceptionDetails `true`이면 취소가 아닌 작업 실패의 throwable을 `Observation.error`로 기록합니다. 기본값은 `false`입니다.
 * @property tagOptions lock 이름과 leader ID를 observation tag로 기록할 때 적용할 sanitization 규칙입니다. 기본값은 민감한 값의 노출을 막는 `LeaderMetricTagOptions.Default`입니다.
 */
data class LeaderObservationOptions(
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
    val tagOptions: LeaderMetricTagOptions = LeaderMetricTagOptions.Default,
): Serializable {

    /** 세 가지 boolean 설정을 지정하고 기본 tag options를 사용하는 호환 생성자입니다. */
    constructor(
        includeLockName: Boolean,
        includeLeaderId: Boolean,
        includeExceptionDetails: Boolean,
    ): this(
        includeLockName = includeLockName,
        includeLeaderId = includeLeaderId,
        includeExceptionDetails = includeExceptionDetails,
        tagOptions = LeaderMetricTagOptions.Default,
    )

    companion object {
        private const val serialVersionUID = 1L
    }
}
