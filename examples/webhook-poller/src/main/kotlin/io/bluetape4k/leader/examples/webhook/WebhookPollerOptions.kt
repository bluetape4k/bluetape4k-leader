package io.bluetape4k.leader.examples.webhook

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `WebhookPollerOptions`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property pollInterval example workflow 계약에서 `pollInterval` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property batchSize example workflow 계약에서 `batchSize` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property maxAttempts example workflow 계약에서 `maxAttempts` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property claimDuration example workflow 계약에서 `claimDuration` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class WebhookPollerOptions(
    val nodeId: String,
    val lockName: String,
    val pollInterval: Duration = 1.seconds,
    val batchSize: Int = 10,
    val maxAttempts: Int = 5,
    val claimDuration: Duration = 30.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
        pollInterval.inWholeMilliseconds.requirePositiveNumber("pollInterval")
        batchSize.requirePositiveNumber("batchSize")
        maxAttempts.requirePositiveNumber("maxAttempts")
        claimDuration.inWholeMilliseconds.requirePositiveNumber("claimDuration")
    }
}
