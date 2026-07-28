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
 * `MongoBackendErrorClassifier`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
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
