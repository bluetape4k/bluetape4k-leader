package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.bucket4j.ratelimit.RateLimitStatus
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Worker that calls an external API only after distributed rate-limit approval.
 *
 * ## Contract
 *
 * All worker nodes share [quotaKey]. A consumed token triggers exactly one
 * [ExternalApiProbe.call]; rejected or errored limiter attempts do not call the
 * external API.
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
