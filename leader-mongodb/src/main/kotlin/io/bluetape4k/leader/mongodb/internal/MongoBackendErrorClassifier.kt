package io.bluetape4k.leader.mongodb.internal

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSecurityException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteException
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind

/**
 * MongoDB backend exception classifier — T9 PR 4 (Issue #79).
 *
 * ## Behavior / Contract
 * - [MongoTimeoutException] / [MongoSocketException] /
 *   [MongoNodeIsRecoveringException] / [MongoNotPrimaryException] → [BackendErrorKind.TRANSIENT]
 *   (retryable — replica set failover / transient network disconnect)
 * - [MongoSecurityException] / [MongoWriteException] / auth-related [MongoCommandException] (code 13/18) →
 *   [BackendErrorKind.NON_TRANSIENT] (permanent error — permission / write failure)
 * - Other [MongoException] → [BackendErrorKind.NON_TRANSIENT] (safe default)
 * - Otherwise → `null` (unclassifiable — delegated to the next classifier in the chain)
 *
 * ## Usage
 * The elector registers this as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier].
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(
 *     MongoBackendErrorClassifier,
 *     CoreBackendErrorClassifier,
 * )
 * ```
 */
internal object MongoBackendErrorClassifier : BackendErrorClassifier {

    private const val AUTH_FAILED = 13
    private const val AUTHENTICATION_FAILED = 18

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is MongoTimeoutException -> BackendErrorKind.TRANSIENT
        is MongoSocketException -> BackendErrorKind.TRANSIENT
        is MongoNodeIsRecoveringException -> BackendErrorKind.TRANSIENT
        is MongoNotPrimaryException -> BackendErrorKind.TRANSIENT
        is MongoSecurityException -> BackendErrorKind.NON_TRANSIENT
        is MongoWriteException -> BackendErrorKind.NON_TRANSIENT
        is MongoCommandException -> when (cause.errorCode) {
            AUTH_FAILED, AUTHENTICATION_FAILED -> BackendErrorKind.NON_TRANSIENT
            else -> BackendErrorKind.NON_TRANSIENT
        }
        is MongoException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
