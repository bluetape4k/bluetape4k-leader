package io.bluetape4k.leader.spring.backend

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

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
        generateSequence(startupFailure(FailingSuspendBeanConfiguration::class.java)) { throwable -> throwable.cause }
            .filterIsInstance<IllegalStateException>()
            .firstOrNull { it.message == "bridge initialization failed" }
            .shouldNotBeNull()
    }

    @Test
    fun `Spring context는 bridge timeout을 startup failure로 전파한다`() {
        generateSequence(startupFailure(TimeoutSuspendBeanConfiguration::class.java)) { throwable -> throwable.cause }
            .filterIsInstance<TimeoutCancellationException>()
            .firstOrNull()
            .shouldNotBeNull()
    }

    @Test
    fun `Spring context는 bridge cancellation을 startup failure로 전파한다`() {
        generateSequence(startupFailure(CancelledSuspendBeanConfiguration::class.java)) { throwable -> throwable.cause }
            .filterIsInstance<CancellationException>()
            .firstOrNull { it.message == "context bean initialization cancelled" }
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

    @Test
    fun `bounded bridge timeout은 dispatcher queue 대기를 포함한다`() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val releaser = Executors.newSingleThreadScheduledExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {
            started.countDown()
            release.await()
        }
        releaser.schedule({ release.countDown() }, 500, TimeUnit.MILLISECONDS)

        try {
            started.await(1, TimeUnit.SECONDS).shouldBeTrue()
            val elapsed = measureTime {
                assertFailsWith<TimeoutCancellationException> {
                    createSuspendBackendBean(
                        timeout = 20.milliseconds,
                        dispatcher = dispatcher,
                    ) { "never-started" }
                }
            }
            (elapsed < 250.milliseconds).shouldBeTrue()
        } finally {
            release.countDown()
            releaser.shutdownNow()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun startupFailure(configuration: Class<*>): Throwable {
        var failure: Throwable? = null
        ApplicationContextRunner()
            .withUserConfiguration(configuration)
            .run { context -> failure = context.startupFailure }
        return failure.shouldNotBeNull()
    }

    @Configuration(proxyBeanMethods = false)
    class FailingSuspendBeanConfiguration {
        @Bean
        fun failingSuspendBean(): String = createSuspendBackendBean {
            throw IllegalStateException("bridge initialization failed")
        }
    }

    @Configuration(proxyBeanMethods = false)
    class TimeoutSuspendBeanConfiguration {
        @Bean
        fun timeoutSuspendBean(): String = createSuspendBackendBean(timeout = 20.milliseconds) {
            delay(Long.MAX_VALUE)
            "never"
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CancelledSuspendBeanConfiguration {
        @Bean
        fun cancelledSuspendBean(): String = createSuspendBackendBean {
            throw CancellationException("context bean initialization cancelled")
        }
    }
}
