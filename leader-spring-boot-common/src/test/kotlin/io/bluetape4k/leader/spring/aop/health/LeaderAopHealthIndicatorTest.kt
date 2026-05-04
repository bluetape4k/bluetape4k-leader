package io.bluetape4k.leader.spring.aop.health

import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Status

/**
 * [LeaderAopHealthIndicator] — backend 호출 없이 로컬 SpEL cache size만 노출.
 */
class LeaderAopHealthIndicatorTest {

    companion object: KLogging()

    @Test
    fun `health - status UP + spelCacheSize detail 노출`() {
        val spel = SpelExpressionEvaluator(embeddedValueResolver = { it })
        val sut = LeaderAopHealthIndicator(spel)

        val health = sut.health()
        health.status shouldBeEqualTo Status.UP
        (health.details["spelCacheSize"] as Long) shouldBeEqualTo 0L
    }

    @Test
    fun `health - SpEL pre-parse 후 cache size 증가`() {
        val spel = SpelExpressionEvaluator(embeddedValueResolver = { it })
        val sut = LeaderAopHealthIndicator(spel)

        val method = SampleService::class.java.getDeclaredMethod("sample")
        spel.preParse("'x-' + #region", method)
        spel.preParse("#region", method)

        val health = sut.health()
        check((health.details["spelCacheSize"] as Long) >= 2L) { "expected cache size >= 2" }
    }

    private class SampleService {
        fun sample() {}
    }
}
