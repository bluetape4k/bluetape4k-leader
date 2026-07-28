package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ExternalApiProbe`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
class ExternalApiProbe {

    private val callCount = AtomicInteger(0)

    val totalCalls: Int
        get() = callCount.get()

    fun call(nodeId: String, itemId: String): ExternalApiCall {
        nodeId.requireNotBlank("nodeId")
        itemId.requireNotBlank("itemId")

        return ExternalApiCall(
            sequence = callCount.incrementAndGet(),
            nodeId = nodeId,
            itemId = itemId,
        )
    }
}

/**
 * `ExternalApiCall`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property sequence example workflow 계약에서 `sequence` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property itemId example workflow 계약에서 `itemId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class ExternalApiCall(
    val sequence: Int,
    val nodeId: String,
    val itemId: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -6675895795563011725L
    }
}
