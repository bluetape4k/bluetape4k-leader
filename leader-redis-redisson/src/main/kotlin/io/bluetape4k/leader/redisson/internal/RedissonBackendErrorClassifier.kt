package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.redisson.client.RedisConnectionException
import org.redisson.client.RedisException
import org.redisson.client.RedisTimeoutException

/**
 * `RedissonBackendErrorClassifier`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object RedissonBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
