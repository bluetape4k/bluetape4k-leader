package io.bluetape4k.leader.spring.backend

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.milliseconds

class SuspendBeanInitializationTest {

    @Test
    fun `bounded bridge는 suspend 초기화 결과를 반환한다`() {
        createSuspendBackendBean { "ready" } shouldBeEqualTo "ready"
    }

    @Test
    fun `bounded bridge는 suspend body를 IO dispatcher에서 실행한다`() {
        val callerThread = Thread.currentThread().name
        val workerThread = createSuspendBackendBean { Thread.currentThread().name }

        workerThread shouldNotBeEqualTo callerThread
    }

    @Test
    fun `Spring context는 bridge 초기화 실패를 startup failure로 전파한다`() {
        var failure: Throwable? = null
        ApplicationContextRunner()
            .withUserConfiguration(FailingSuspendBeanConfiguration::class.java)
            .run { context -> failure = context.startupFailure }

        generateSequence(failure.shouldNotBeNull()) { throwable -> throwable.cause }
            .filterIsInstance<IllegalStateException>()
            .firstOrNull { it.message == "bridge initialization failed" }
            .shouldNotBeNull()
    }

    @Test
    fun `bounded bridge는 timeout을 호출자에게 전파한다`() {
        assertFailsWith<TimeoutCancellationException> {
            createSuspendBackendBean(timeout = 20.milliseconds) {
                delay(Long.MAX_VALUE)
            }
        }
    }

    @Test
    fun `bounded bridge는 cancellation을 호출자에게 전파한다`() {
        val thrown = assertFailsWith<CancellationException> {
            createSuspendBackendBean {
                throw CancellationException("bean initialization cancelled")
            }
        }

        thrown.message shouldBeEqualTo "bean initialization cancelled"
    }

    @Configuration(proxyBeanMethods = false)
    class FailingSuspendBeanConfiguration {
        @Bean
        fun failingSuspendBean(): String = createSuspendBackendBean {
            throw IllegalStateException("bridge initialization failed")
        }
    }
}
