package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.redisson.client.RedisConnectionException
import org.redisson.client.RedisException
import org.redisson.client.RedisTimeoutException

/**
 * `RedissonBackendErrorClassifier`는 Redis Redisson 예외를 `BackendErrorKind`로 분류해 재시도·중단 판단에 전달합니다.
 *
 */
internal object RedissonBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisException        -> BackendErrorKind.NON_TRANSIENT
        else                     -> null
    }
}
