package io.bluetape4k.leader.examples.warmer

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * `CachePartitionWarmerOptions`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property partitions example workflow 계약에서 `partitions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockNamePrefix example workflow 계약에서 `lockNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property waitTime example workflow 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime example workflow 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class CachePartitionWarmerOptions(
    val nodeId: String,
    val partitions: List<String>,
    val lockNamePrefix: String = "warmer",
    val waitTime: Duration = 5.seconds,
    val leaseTime: Duration = 1.minutes,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockNamePrefix.requireNotBlank("lockNamePrefix")
        partitions.requireNotEmpty("partitions")
        partitions.forEachIndexed { idx, p -> p.requireNotBlank("partitions[$idx]") }
        waitTime.inWholeMilliseconds.requirePositiveNumber("waitTime")
        leaseTime.inWholeMilliseconds.requirePositiveNumber("leaseTime")
    }
}
