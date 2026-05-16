package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException

/**
 * Lettuce backend exception classifier — T7 PR 2.
 *
 * ## Behavior / Contract
 * - [RedisCommandTimeoutException] / [RedisConnectionException] → [BackendErrorKind.TRANSIENT] (retryable)
 * - [RedisCommandExecutionException] → [BackendErrorKind.NON_TRANSIENT] (Lua syntax error, ACL failure, etc. — permanent error)
 * - Other → `null` (unclassified — delegated to the next classifier in the chain)
 *
 * ## Usage
 * Registered as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] by the elector.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(
 *     LettuceBackendErrorClassifier,
 *     CoreBackendErrorClassifier,
 * )
 * ```
 */
internal object LettuceBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisCommandTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisCommandExecutionException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
