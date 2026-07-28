package io.bluetape4k.leader.micrometer

import java.io.Serializable

/**
 * `LeaderObservationOptions`는 Micrometer observability에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property includeLockName Micrometer observability 계약에서 `includeLockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property includeLeaderId Micrometer observability 계약에서 `includeLeaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property includeExceptionDetails Micrometer observability 계약에서 `includeExceptionDetails` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property tagOptions Micrometer observability 계약에서 `tagOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderObservationOptions(
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
    val tagOptions: LeaderMetricTagOptions = LeaderMetricTagOptions.Default,
) : Serializable {

    /**
     * Micrometer observability 계약을 설명하는 한국어 KDoc입니다.
     */
    constructor(
        includeLockName: Boolean,
        includeLeaderId: Boolean,
        includeExceptionDetails: Boolean,
    ) : this(
        includeLockName = includeLockName,
        includeLeaderId = includeLeaderId,
        includeExceptionDetails = includeExceptionDetails,
        tagOptions = LeaderMetricTagOptions.Default,
    )

    companion object {
        private const val serialVersionUID = 1L
    }
}
