package io.bluetape4k.leader.hazelcast.internal

import com.hazelcast.core.HazelcastException
import com.hazelcast.spi.exception.RetryableHazelcastException
import com.hazelcast.spi.exception.TargetNotMemberException
import com.hazelcast.spi.exception.WrongTargetException
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind

/**
 * `HazelcastBackendErrorClassifier`는 Hazelcast 예외를 `BackendErrorKind`로 분류해 재시도·중단 판단에 전달합니다.
 *
 */
internal object HazelcastBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is WrongTargetException     -> BackendErrorKind.TRANSIENT
        is TargetNotMemberException -> BackendErrorKind.TRANSIENT
        is RetryableHazelcastException -> BackendErrorKind.TRANSIENT
        is HazelcastException       -> BackendErrorKind.NON_TRANSIENT
        else                        -> null
    }
}
