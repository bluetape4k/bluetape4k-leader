package io.bluetape4k.leader.internal

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * `CoreBackendErrorClassifier` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
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
