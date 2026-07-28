package io.bluetape4k.leader.hazelcast.internal

import com.hazelcast.core.HazelcastException
import com.hazelcast.spi.exception.RetryableHazelcastException
import com.hazelcast.spi.exception.TargetNotMemberException
import com.hazelcast.spi.exception.WrongTargetException
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind

/**
 * `HazelcastBackendErrorClassifier`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object HazelcastBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RetryableHazelcastException -> BackendErrorKind.TRANSIENT
        is TargetNotMemberException -> BackendErrorKind.TRANSIENT
        is WrongTargetException -> BackendErrorKind.TRANSIENT
        is HazelcastException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
