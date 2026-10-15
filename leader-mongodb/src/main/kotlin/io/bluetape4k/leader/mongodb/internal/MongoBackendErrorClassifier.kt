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
 * `MongoBackendErrorClassifier`는 MongoDB 예외를 `BackendErrorKind`로 분류해 재시도·중단 판단에 전달합니다.
 *
 */
internal object MongoBackendErrorClassifier: BackendErrorClassifier {

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
