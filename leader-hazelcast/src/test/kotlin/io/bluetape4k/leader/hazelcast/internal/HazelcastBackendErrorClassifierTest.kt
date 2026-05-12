package io.bluetape4k.leader.hazelcast.internal

import com.hazelcast.core.HazelcastException
import com.hazelcast.spi.exception.RetryableHazelcastException
import com.hazelcast.spi.exception.TargetNotMemberException
import com.hazelcast.spi.exception.WrongTargetException
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastBackendErrorClassifierTest {

    companion object : KLogging()

    @Test
    fun `RetryableHazelcastException 은 TRANSIENT`() {
        val ex = RetryableHazelcastException("timeout")
        HazelcastBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `TargetNotMemberException 은 TRANSIENT`() {
        val ex = TargetNotMemberException("node left")
        HazelcastBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `WrongTargetException 은 TRANSIENT`() {
        val ex = WrongTargetException("wrong node")
        HazelcastBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `일반 HazelcastException 은 NON_TRANSIENT`() {
        val ex = HazelcastException("permanent error")
        HazelcastBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `HazelcastException 이 아닌 예외는 null`() {
        HazelcastBackendErrorClassifier.classify(RuntimeException("unrelated")).shouldBeNull()
        HazelcastBackendErrorClassifier.classify(IllegalStateException("state")).shouldBeNull()
        HazelcastBackendErrorClassifier.classify(NullPointerException()).shouldBeNull()
    }

    @Test
    fun `RetryableHazelcastException 하위 타입도 TRANSIENT`() {
        // RetryableHazelcastException 은 HazelcastException 의 하위 타입이지만
        // when 분기에서 먼저 매칭됨을 보장
        val ex = object : RetryableHazelcastException("sub") {}
        HazelcastBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }
}
