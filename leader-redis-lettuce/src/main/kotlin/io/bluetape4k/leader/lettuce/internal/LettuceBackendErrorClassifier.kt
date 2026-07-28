package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException

/**
 * `LettuceBackendErrorClassifier`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object LettuceBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisCommandTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisCommandExecutionException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
