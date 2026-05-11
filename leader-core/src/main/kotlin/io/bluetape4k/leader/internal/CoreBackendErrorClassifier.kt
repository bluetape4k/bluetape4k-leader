package io.bluetape4k.leader.internal

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * JDK / 공통 exception 분류 — backend module 의존성 없음.
 *
 * ## 동작/계약
 * 각 backend module 의 [CompositeBackendErrorClassifier] 가 backend-specific classifier 시도 후
 * `null` 반환 시 이 core classifier 에 위임.
 *
 * - [OutOfMemoryError], [StackOverflowError], [LinkageError] → [BackendErrorKind.FATAL]
 * - [SQLTransientException], [SQLRecoverableException] → [BackendErrorKind.TRANSIENT]
 * - [SQLNonTransientException] → [BackendErrorKind.NON_TRANSIENT]
 * - [SocketTimeoutException], [ConnectException] → [BackendErrorKind.TRANSIENT]
 * - 그 외 → `null` (분류 불가)
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
