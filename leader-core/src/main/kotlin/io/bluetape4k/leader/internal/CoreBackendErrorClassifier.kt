package io.bluetape4k.leader.internal

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * JDK / common exception classifier — no backend module dependencies.
 *
 * ## Behavior / Contract
 * Each backend module's [CompositeBackendErrorClassifier] delegates to this core classifier
 * when its backend-specific classifier returns `null`.
 *
 * - [OutOfMemoryError], [StackOverflowError], [LinkageError] → [BackendErrorKind.FATAL]
 * - [SQLTransientException], [SQLRecoverableException] → [BackendErrorKind.TRANSIENT]
 * - [SQLNonTransientException] → [BackendErrorKind.NON_TRANSIENT]
 * - [SocketTimeoutException], [ConnectException] → [BackendErrorKind.TRANSIENT]
 * - Other → `null` (unclassifiable)
 *
 * ## Example
 * ```kotlin
 * CoreBackendErrorClassifier.classify(OutOfMemoryError())        // FATAL
 * CoreBackendErrorClassifier.classify(SQLTransientException("")) // TRANSIENT
 * CoreBackendErrorClassifier.classify(RuntimeException("x"))    // null
 * ```
 */
internal object CoreBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is OutOfMemoryError, is StackOverflowError, is LinkageError -> BackendErrorKind.FATAL
        is SQLTransientException, is SQLRecoverableException -> BackendErrorKind.TRANSIENT
        is SQLNonTransientException -> BackendErrorKind.NON_TRANSIENT
        is SocketTimeoutException, is ConnectException -> BackendErrorKind.TRANSIENT
        else -> null  // 분류 불가 — CompositeBackendErrorClassifier 가 NON_TRANSIENT default 처리
    }
}
