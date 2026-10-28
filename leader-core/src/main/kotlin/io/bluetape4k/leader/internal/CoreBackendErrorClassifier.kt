package io.bluetape4k.leader.internal

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * JDBC 및 socket 계층에서 공통으로 알아볼 수 있는 예외를 분류합니다.
 * 알 수 없는 예외는 `null`을 반환해 backend별 분류기가 추가로 판단하게 합니다.
 */
internal object CoreBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is OutOfMemoryError, is StackOverflowError, is LinkageError -> BackendErrorKind.FATAL
        is SQLTransientException, is SQLRecoverableException -> BackendErrorKind.TRANSIENT
        is SQLNonTransientException                          -> BackendErrorKind.NON_TRANSIENT
        is SocketTimeoutException, is ConnectException       -> BackendErrorKind.TRANSIENT
        else                                                 -> null  // 분류 불가 — CompositeBackendErrorClassifier 가 NON_TRANSIENT default 처리
    }
}
