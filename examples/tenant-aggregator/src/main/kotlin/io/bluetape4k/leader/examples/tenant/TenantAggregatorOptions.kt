package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `TenantAggregatorOptions`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property tenants example workflow 계약에서 `tenants` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockNamePrefix example workflow 계약에서 `lockNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property pollInterval example workflow 계약에서 `pollInterval` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property waitTime example workflow 계약에서 `waitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTime example workflow 계약에서 `leaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class TenantAggregatorOptions(
    val nodeId: String,
    val tenants: List<String>,
    val lockNamePrefix: String = "tenant-aggregator",
    val pollInterval: Duration = 5.seconds,
    val waitTime: Duration = 1.seconds,
    val leaseTime: Duration = 60.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockNamePrefix.requireNotBlank("lockNamePrefix")
        tenants.requireNotEmpty("tenants")
        tenants.forEachIndexed { idx, t -> t.requireNotBlank("tenants[$idx]") }
        pollInterval.inWholeMilliseconds.requirePositiveNumber("pollInterval")
        waitTime.inWholeMilliseconds.requirePositiveNumber("waitTime")
        leaseTime.inWholeMilliseconds.requirePositiveNumber("leaseTime")
    }
}
