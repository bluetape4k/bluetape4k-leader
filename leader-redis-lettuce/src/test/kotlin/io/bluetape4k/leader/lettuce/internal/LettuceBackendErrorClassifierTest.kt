package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.logging.KLogging
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceBackendErrorClassifierTest {

    companion object : KLogging()

    @Test
    fun `RedisCommandTimeoutException 은 TRANSIENT`() {
        val ex = RedisCommandTimeoutException("command timed out")
        LettuceBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `RedisConnectionException 은 TRANSIENT`() {
        val ex = RedisConnectionException("connection lost")
        LettuceBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `RedisCommandExecutionException 은 NON_TRANSIENT`() {
        val ex = RedisCommandExecutionException("ERR syntax error")
        LettuceBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `Redis 예외가 아닌 경우 null 반환`() {
        LettuceBackendErrorClassifier.classify(RuntimeException("unrelated")).shouldBeNull()
        LettuceBackendErrorClassifier.classify(IllegalStateException("state")).shouldBeNull()
        LettuceBackendErrorClassifier.classify(NullPointerException()).shouldBeNull()
    }

    @Test
    fun `RedisCommandExecutionException 하위 타입도 NON_TRANSIENT`() {
        val ex = object : RedisCommandExecutionException("Lua error") {}
        LettuceBackendErrorClassifier.classify(ex) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }
}
