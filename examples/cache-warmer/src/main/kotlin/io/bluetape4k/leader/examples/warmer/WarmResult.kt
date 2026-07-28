package io.bluetape4k.leader.examples.warmer

/**
 * `WarmResult`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property warmed example workflow 계약에서 `warmed` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property skipped example workflow 계약에서 `skipped` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property failed example workflow 계약에서 `failed` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class WarmResult(
    val nodeId: String,
    val warmed: List<String>,
    val skipped: List<String>,
    val failed: Map<String, String>,
)
