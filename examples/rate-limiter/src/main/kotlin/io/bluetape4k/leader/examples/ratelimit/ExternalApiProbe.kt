package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory external API probe used by the rate-limiter example.
 *
 * ## Contract
 *
 * Every successful call increments [totalCalls] exactly once. Rejected limiter
 * attempts must not call this probe, so tests can assert the global API-call
 * ceiling without relying on logs.
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

data class ExternalApiCall(
    val sequence: Int,
    val nodeId: String,
    val itemId: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -6675895795563011725L
    }
}
