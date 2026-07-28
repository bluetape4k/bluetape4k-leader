package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.apache.zookeeper.KeeperException

/**
 * `ZooKeeperBackendErrorClassifier`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object ZooKeeperBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is KeeperException.ConnectionLossException -> BackendErrorKind.TRANSIENT
        is KeeperException.OperationTimeoutException -> BackendErrorKind.TRANSIENT
        is KeeperException.SessionExpiredException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException.SessionMovedException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
