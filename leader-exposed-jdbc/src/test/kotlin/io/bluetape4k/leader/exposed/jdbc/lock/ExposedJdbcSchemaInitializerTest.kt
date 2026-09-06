package io.bluetape4k.leader.exposed.jdbc.lock

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.leader.exposed.testing.databaseUrlRedactionContractTests
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExposedJdbcSchemaInitializerTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    @TestFactory
    fun `database URL redaction contract`() =
        databaseUrlRedactionContractTests(ExposedJdbcSchemaInitializer::sanitizeUrl)

    @org.junit.jupiter.api.Test
    fun `초기화 실패 로그는 예외의 raw URL을 기록하지 않는다`() {
        val secret = "jdbc-log-secret"
        val initializerLogger = LoggerFactory.getLogger(ExposedJdbcSchemaInitializer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        initializerLogger.addAppender(appender)

        try {
            ExposedJdbcSchemaInitializer.logInitializationFailure(
                "jdbc:postgresql://alice:$secret@db.example.com/leader",
                IllegalStateException("연결 실패: jdbc:postgresql://alice:$secret@db.example.com/leader"),
            )

            val warning = appender.list.first { event ->
                event.level == Level.WARN && "리더 선출 스키마 초기화 실패" in event.formattedMessage
            }
            warning.formattedMessage shouldNotContain secret
            warning.throwableProxy.shouldBeNull()
        } finally {
            initializerLogger.detachAppender(appender)
            appender.stop()
        }
    }

    // --- ensureSchema 동시성 ---

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `ensureSchema - 다중 스레드 동시 호출에도 예외 없이 1회만 초기화된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        ExposedJdbcSchemaInitializer.resetFor(db)

        val threadCount = 16
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val errorCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(threadCount) {
                executor.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        ExposedJdbcSchemaInitializer.ensureSchema(db)
                    } catch (e: Throwable) {
                        log.warn(e) { "ensureSchema 예외 발생" }
                        errorCount.incrementAndGet()
                    }
                }
            }
            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        errorCount.get() shouldBeEqualTo 0
    }
}
