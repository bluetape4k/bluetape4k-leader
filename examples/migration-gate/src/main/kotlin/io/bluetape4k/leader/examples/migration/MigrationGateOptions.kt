package io.bluetape4k.leader.examples.migration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `MigrationGateOptions`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property waitTime example workflow 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime example workflow 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class MigrationGateOptions(
    val nodeId: String,
    val lockName: String,
    val waitTime: Duration = 30.seconds,
    val leaseTime: Duration = 5.minutes,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
        waitTime.inWholeMilliseconds.requirePositiveNumber("waitTime")
        leaseTime.inWholeMilliseconds.requirePositiveNumber("leaseTime")
    }
}
