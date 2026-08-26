package io.bluetape4k.leader.spring.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        val bodyStarted = AtomicBoolean()
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
                    ) {
                        bodyStarted.set(true)
                        "never-started"
                    }
                }
            }
            (elapsed < 250.milliseconds).shouldBeTrue()
        } finally {
            release.countDown()
            releaser.shutdownNow()
            dispatcher.close()
            executor.shutdownNow()
        }
        executor.awaitTermination(1, TimeUnit.SECONDS).shouldBeTrue()
        bodyStarted.get().shouldBeFalse()
    }

    @Test
    fun `bounded bridge timeout은 초기화 작업 cleanup 완료까지 기다린다`() {
        val caller = Executors.newSingleThreadExecutor()
        val dispatcherExecutor = Executors.newSingleThreadExecutor()
        val dispatcher = dispatcherExecutor.asCoroutineDispatcher()
        val started = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val bridgeReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        try {
            caller.submit {
                try {
                    createSuspendBackendBean(
                        timeout = 20.milliseconds,
                        dispatcher = dispatcher,
                        cleanupTimeout = 500.milliseconds,
                    ) {
                        started.countDown()
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            cleanupStarted.countDown()
                            // coroutine 취소에 응답하지 않는 cleanup을 재현하기 위해
                            // 의도적으로 blocking latch를 사용합니다.
                            cleanupRelease.await()
                        }
                    }
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                } finally {
                    bridgeReturned.countDown()
                }
            }

            started.await(1, TimeUnit.SECONDS).shouldBeTrue()
            cleanupStarted.await(1, TimeUnit.SECONDS).shouldBeTrue()
            bridgeReturned.await(100, TimeUnit.MILLISECONDS).shouldBeFalse()

            cleanupRelease.countDown()
            bridgeReturned.await(1, TimeUnit.SECONDS).shouldBeTrue()
            (failure.get() is TimeoutCancellationException).shouldBeTrue()
        } finally {
            cleanupRelease.countDown()
            caller.shutdownNow()
            dispatcher.close()
            dispatcherExecutor.shutdownNow()
        }
        caller.awaitTermination(1, TimeUnit.SECONDS).shouldBeTrue()
        dispatcherExecutor.awaitTermination(1, TimeUnit.SECONDS).shouldBeTrue()
    }

    @Test
    fun `bounded bridge timeout은 non-cooperative cleanup을 grace timeout으로 제한한다`() {
        val caller = Executors.newSingleThreadExecutor()
        val dispatcherExecutor = Executors.newSingleThreadExecutor()
        val dispatcher = dispatcherExecutor.asCoroutineDispatcher()
        val started = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val bridgeReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val logger = LoggerFactory.getLogger(BACKEND_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            caller.submit {
                try {
                    createSuspendBackendBean(
                        timeout = 20.milliseconds,
                        dispatcher = dispatcher,
                        cleanupTimeout = 200.milliseconds,
                        operationName = "test-suspend-bean",
                    ) {
                        started.countDown()
                        try {
                            delay(Long.MAX_VALUE)
                        } finally {
                            cleanupStarted.countDown()
                            // coroutine 취소에 응답하지 않는 cleanup을 재현하기 위해
                            // 의도적으로 blocking latch를 사용합니다.
                            cleanupRelease.await()
                        }
                    }
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                } finally {
                    bridgeReturned.countDown()
                }
            }

            started.await(1, TimeUnit.SECONDS).shouldBeTrue()
            cleanupStarted.await(1, TimeUnit.SECONDS).shouldBeTrue()
            bridgeReturned.await(50, TimeUnit.MILLISECONDS).shouldBeFalse()
            bridgeReturned.await(2, TimeUnit.SECONDS).shouldBeTrue()
            (failure.get() is TimeoutCancellationException).shouldBeTrue()

            val warning = appender.list.firstOrNull {
                it.level == Level.WARN && "operationName=test-suspend-bean" in it.formattedMessage
            }.shouldNotBeNull()
            warning.formattedMessage shouldContain "cleanup did not complete within"
        } finally {
            cleanupRelease.countDown()
            logger.detachAppender(appender)
            appender.stop()
            caller.shutdownNow()
            dispatcher.close()
            dispatcherExecutor.shutdownNow()
        }
        caller.awaitTermination(1, TimeUnit.SECONDS).shouldBeTrue()
        dispatcherExecutor.awaitTermination(1, TimeUnit.SECONDS).shouldBeTrue()
    }

    @Test
    fun `bounded bridge는 cleanup timeout을 positive finite으로 검증한다`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            createSuspendBackendBean(cleanupTimeout = 0.milliseconds) { "never" }
        }

        thrown.message shouldBeEqualTo
            "suspend backend bean cleanup timeout must be positive and finite: 0s"
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

    companion object {
        private const val BACKEND_LOGGER_NAME = "io.bluetape4k.leader.spring.backend"
    }
}
