package io.bluetape4k.leader.examples.batch

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class BatchSchedulerResultTest: AbstractBatchSchedulerTest() {
    @Test
    fun `null을 반환한 실제 작업은 skip으로 기록하지 않는다`() {
        val logger = LoggerFactory.getLogger(BatchScheduler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            newConnection().use { connection ->
                val node = randomLockName()
                val scheduler = BatchScheduler(node, connection, randomLockName())
                var ran = false
                scheduler.run<String?> { ran = true; null } shouldBeEqualTo null
                ran.shouldBeTrue()
                val messages = appender.list.filter { it.formattedMessage.contains("[$node]") }
                messages.count { it.formattedMessage.contains("Job 실행 완료") } shouldBeEqualTo 1
                messages.count { it.formattedMessage.contains("skip.") } shouldBeEqualTo 0
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `실제 경합에서만 skip 로그를 기록한다`() {
        val logger = LoggerFactory.getLogger(BatchScheduler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        logger.addAppender(appender)
        try {
            newConnection().use { holderConnection ->
                newConnection().use { contenderConnection ->
                    val lock = randomLockName()
                    val holder = BatchScheduler("holder", holderConnection, lock)
                    val node = randomLockName()
                    val contender = BatchScheduler(node, contenderConnection, lock, waitTime = 10.milliseconds)
                    val holding = executor.submit {
                        holder.run { entered.countDown(); release.await(5, TimeUnit.SECONDS).shouldBeTrue() }
                    }
                    try {
                        entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                        contender.run { error("경합 중 작업을 실행하면 안 됩니다") } shouldBeEqualTo null
                        appender.list.count {
                            it.formattedMessage.contains("[$node]") && it.formattedMessage.contains("skip.")
                        } shouldBeEqualTo 1
                    } finally {
                        release.countDown()
                        holding.get(5, TimeUnit.SECONDS)
                    }
                }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["action", "cancel", "interrupt"])
    fun `오류 원본과 취소 interrupt 계약을 보존한다`(mode: String) {
        newConnection().use { connection ->
            val scheduler = BatchScheduler("failure", connection, randomLockName())
            val original = when (mode) {
                "cancel" -> CancellationException("cancel")
                "interrupt" -> InterruptedException("interrupt")
                else -> IllegalStateException("action")
            }
            try {
                val actual = assertFailsWith<Exception> { scheduler.run<Unit> { throw original } }
                (actual === original).shouldBeTrue()
                if (mode == "interrupt") Thread.currentThread().isInterrupted.shouldBeTrue()
            } finally {
                Thread.interrupted()
            }
            scheduler.run { 42 } shouldBeEqualTo 42
        }
    }
}
