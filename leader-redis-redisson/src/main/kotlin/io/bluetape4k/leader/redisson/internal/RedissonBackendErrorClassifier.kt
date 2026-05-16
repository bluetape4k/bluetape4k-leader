package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.redisson.client.RedisConnectionException
import org.redisson.client.RedisException
import org.redisson.client.RedisTimeoutException

/**
 * Redisson backend exception classifier — T7 PR 3 (Issue #79).
 *
 * ## Behavior / Contract
 * - [RedisTimeoutException] / [RedisConnectionException] → [BackendErrorKind.TRANSIENT] (retryable)
 * - [RedisException] (other than timeout/connection) → [BackendErrorKind.NON_TRANSIENT] (Lua execution error, ACL failure, etc.)
 * - Other → `null` (unclassified — delegated to the next classifier in the chain)
 *
 * ## Usage
 * Registered as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] by the elector.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)
 * ```
 */
internal object RedissonBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
