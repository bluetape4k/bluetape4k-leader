package io.bluetape4k.leader.hazelcast.internal

import com.hazelcast.core.HazelcastException
import com.hazelcast.spi.exception.RetryableHazelcastException
import com.hazelcast.spi.exception.TargetNotMemberException
import com.hazelcast.spi.exception.WrongTargetException
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind

/**
 * Hazelcast backend exception classifier — T12 PR 7 (Issue #79).
 *
 * ## Behavior / Contract
 * - [RetryableHazelcastException] / [TargetNotMemberException] / [WrongTargetException] →
 *   [BackendErrorKind.TRANSIENT] (retryable — cluster event / member departure)
 * - Other [HazelcastException] → [BackendErrorKind.NON_TRANSIENT] (safe default)
 * - Other → `null` (unclassifiable — delegated to the next classifier in the chain)
 *
 * ## Usage
 * Registered as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] by the elector.
 */
internal object HazelcastBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RetryableHazelcastException -> BackendErrorKind.TRANSIENT
        is TargetNotMemberException -> BackendErrorKind.TRANSIENT
        is WrongTargetException -> BackendErrorKind.TRANSIENT
        is HazelcastException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
