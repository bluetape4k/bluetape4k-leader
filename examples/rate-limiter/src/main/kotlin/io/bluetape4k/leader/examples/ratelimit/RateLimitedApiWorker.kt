package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.bucket4j.ratelimit.RateLimitStatus
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `RateLimitedApiWorker`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property rateLimiter example workflow 계약에서 사용하는 속성입니다.
 * @property externalApi example workflow 계약에서 사용하는 속성입니다.
 * @property quotaKey example workflow 계약에서 사용하는 속성입니다.
 */
class RateLimitedApiWorker(
    private val nodeId: String,
    private val rateLimiter: DistributedSuspendRateLimiter,
    private val externalApi: ExternalApiProbe,
    private val quotaKey: String,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        quotaKey.requireNotBlank("quotaKey")
    }

    companion object: KLogging()

    suspend fun call(itemId: String): WorkerCallReport {
        itemId.requireNotBlank("itemId")

        val result = rateLimiter.consume(quotaKey, 1)
        return when (result.status) {
            RateLimitStatus.CONSUMED -> {
                val apiCall = externalApi.call(nodeId, itemId)
                log.info { "[$nodeId] CONSUMED item=$itemId sequence=${apiCall.sequence}" }
                WorkerCallReport(
                    nodeId = nodeId,
                    itemId = itemId,
                    status = RateLimiterDemoStatus.CONSUMED,
                    availableTokens = result.availableTokens,
                    apiCallSequence = apiCall.sequence,
                )
            }

            RateLimitStatus.REJECTED -> {
                log.info { "[$nodeId] REJECTED item=$itemId availableTokens=${result.availableTokens}" }
                WorkerCallReport(
                    nodeId = nodeId,
                    itemId = itemId,
                    status = RateLimiterDemoStatus.REJECTED,
                    availableTokens = result.availableTokens,
                )
            }

            RateLimitStatus.ERROR -> {
                log.warn { "[$nodeId] ERROR item=$itemId message=${result.errorMessage}" }
                WorkerCallReport(
                    nodeId = nodeId,
                    itemId = itemId,
                    status = RateLimiterDemoStatus.ERROR,
                    availableTokens = result.availableTokens,
                    errorMessage = result.errorMessage,
                )
            }
        }
    }
}

/**
 * `WorkerCallReport`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property itemId example workflow 계약에서 `itemId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property availableTokens example workflow 계약에서 `availableTokens` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property apiCallSequence example workflow 계약에서 `apiCallSequence` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property errorMessage example workflow 계약에서 `errorMessage` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class WorkerCallReport(
    val nodeId: String,
    val itemId: String,
    val status: RateLimiterDemoStatus,
    val availableTokens: Long,
    val apiCallSequence: Int? = null,
    val errorMessage: String? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -7074728663661467667L
    }
}
