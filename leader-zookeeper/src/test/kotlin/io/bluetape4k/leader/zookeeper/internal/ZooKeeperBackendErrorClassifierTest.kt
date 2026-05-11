package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.logging.KLogging
import org.apache.zookeeper.KeeperException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [ZooKeeperBackendErrorClassifier] 단위 테스트.
 *
 * 모든 분기 (TRANSIENT / NON_TRANSIENT / null) 를 직접 검증해 backend exception 분류 계약을 보장합니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZooKeeperBackendErrorClassifierTest {

    companion object: KLogging()

    @Test
    fun `ConnectionLossException 은 TRANSIENT 로 분류된다`() {
        val cause = KeeperException.create(KeeperException.Code.CONNECTIONLOSS)
        ZooKeeperBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `OperationTimeoutException 은 TRANSIENT 로 분류된다`() {
        val cause = KeeperException.create(KeeperException.Code.OPERATIONTIMEOUT)
        ZooKeeperBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `SessionExpiredException 은 NON_TRANSIENT 로 분류된다`() {
        val cause = KeeperException.create(KeeperException.Code.SESSIONEXPIRED)
        ZooKeeperBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `SessionMovedException 은 NON_TRANSIENT 로 분류된다`() {
        val cause = KeeperException.create(KeeperException.Code.SESSIONMOVED)
        ZooKeeperBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `그 외 KeeperException 은 NON_TRANSIENT 로 분류된다`() {
        val cause = KeeperException.create(KeeperException.Code.NONODE)
        ZooKeeperBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `KeeperException 이 아닌 예외는 null 을 반환한다 (chain 위임)`() {
        ZooKeeperBackendErrorClassifier.classify(RuntimeException("other")).shouldBeNull()
        ZooKeeperBackendErrorClassifier.classify(IllegalStateException("other")).shouldBeNull()
    }
}
