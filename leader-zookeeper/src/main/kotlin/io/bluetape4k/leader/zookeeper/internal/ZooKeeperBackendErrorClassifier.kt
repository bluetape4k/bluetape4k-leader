package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.apache.zookeeper.KeeperException

/**
 * `ZooKeeperBackendErrorClassifier`는 ZooKeeper 예외를 `BackendErrorKind`로 분류해 재시도·중단 판단에 전달합니다.
 *
 */
internal object ZooKeeperBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is KeeperException.ConnectionLossException -> BackendErrorKind.TRANSIENT
        is KeeperException.OperationTimeoutException -> BackendErrorKind.TRANSIENT
        is KeeperException.SessionExpiredException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException.SessionMovedException   -> BackendErrorKind.NON_TRANSIENT
        is KeeperException                         -> BackendErrorKind.NON_TRANSIENT
        else                                       -> null
    }
}
