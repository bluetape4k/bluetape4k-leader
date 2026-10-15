package io.bluetape4k.leader.metrics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeInstanceOf
import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderRecorderContextDropLogTest {

    companion object: KLogging()

    private lateinit var dropLog: LeaderRecorderContextDropLog

    @BeforeEach
    fun setup() {
        dropLog = LeaderRecorderContextDropLog()
        LeaderRecorderContextDropLog.setGlobal(dropLog)
    }

    @Test
    fun `warnOnDrop - Unknown context - no counter increment`() {
        dropLog.warnOnDrop(FakeRecorder::class, LeaderAopMetricsContext.Unknown)
        dropLog.droppedCount() shouldBeEqualTo 0L
    }

    @Test
    fun `warnOnDrop - Identified context - increments counter on first call`() {
        val ctx = LeaderAopMetricsContext.Identified("node-a", LeaderIdSource.LITERAL)
        dropLog.warnOnDrop(FakeRecorder::class, ctx)
        dropLog.droppedCount() shouldBeEqualTo 1L
    }

    @Test
    fun `warnOnDrop - Identified context - increments counter on repeated calls`() {
        val ctx = LeaderAopMetricsContext.Identified("node-a", LeaderIdSource.LITERAL)
        repeat(5) { dropLog.warnOnDrop(FakeRecorder::class, ctx) }
        dropLog.droppedCount() shouldBeEqualTo 5L
    }

    @Test
    fun `warnOnDrop - different recorder classes - each tracked independently`() {
        val ctx = LeaderAopMetricsContext.Identified("node-a", LeaderIdSource.AUTO)
        dropLog.warnOnDrop(FakeRecorder::class, ctx)
        dropLog.warnOnDrop(AnotherFakeRecorder::class, ctx)
        dropLog.droppedCount() shouldBeEqualTo 2L
    }

    @Test
    fun `cacheSize must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderRecorderContextDropLog(cacheSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRecorderContextDropLog(cacheSize = -1)
        }
    }

    @Test
    fun `cacheSize bounds warning suppression with access-order LRU eviction`() {
        val tiny = LeaderRecorderContextDropLog(cacheSize = 2)
        val ctx = LeaderAopMetricsContext.Identified("node-a", LeaderIdSource.LITERAL)

        val warnings = captureWarningMessages {
            tiny.warnOnDrop(FakeRecorder::class, ctx)
            tiny.warnOnDrop(AnotherFakeRecorder::class, ctx)
            tiny.warnOnDrop(FakeRecorder::class, ctx)
            tiny.warnOnDrop(ThirdFakeRecorder::class, ctx)
            tiny.warnOnDrop(AnotherFakeRecorder::class, ctx)
        }

        warnings.count { FakeRecorder::class.qualifiedName!! in it } shouldBeEqualTo 1
        warnings.count { AnotherFakeRecorder::class.qualifiedName!! in it } shouldBeEqualTo 2
        warnings.count { ThirdFakeRecorder::class.qualifiedName!! in it } shouldBeEqualTo 1
        tiny.droppedCount() shouldBeEqualTo 5L
    }

    @Test
    fun `warnOnDrop - concurrent calls log once while recorder class remains cached`() {
        val tiny = LeaderRecorderContextDropLog(cacheSize = 1)
        val ctx = LeaderAopMetricsContext.Identified("node-a", LeaderIdSource.LITERAL)
        val workerCount = 8
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        val warnings = try {
            captureWarningMessages {
                val tasks = (1..workerCount).map {
                    executor.submit {
                        ready.countDown()
                        start.await()
                        tiny.warnOnDrop(FakeRecorder::class, ctx)
                    }
                }
                ready.await(5, TimeUnit.SECONDS) shouldBe true
                start.countDown()
                tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            }
        } finally {
            executor.shutdownNow()
        }

        warnings.count { FakeRecorder::class.qualifiedName!! in it } shouldBeEqualTo 1
        tiny.droppedCount() shouldBeEqualTo workerCount.toLong()
    }

    @Test
    fun `Empty sentinel is Unknown - no drop counted`() {
        val ctx = LeaderAopMetricsContext.Empty
        ctx.shouldBeInstanceOf<LeaderAopMetricsContext.Unknown>()

        dropLog.warnOnDrop(FakeRecorder::class, ctx)
        dropLog.droppedCount() shouldBeEqualTo 0L
    }

    @Test
    fun `Identified context contains leaderId and source`() {
        val ctx = LeaderAopMetricsContext.Identified("leader-123", LeaderIdSource.SPEL)
        ctx.leaderId shouldBeEqualTo "leader-123"
        ctx.leaderIdSource shouldBeEqualTo LeaderIdSource.SPEL
    }

    @Test
    fun `global holder - setGlobal replaces instance`() {
        val fresh = LeaderRecorderContextDropLog()
        LeaderRecorderContextDropLog.setGlobal(fresh)
        LeaderRecorderContextDropLog.global() shouldBe fresh
    }

    @Test
    fun `global holder - fresh instance starts at zero`() {
        val ctx = LeaderAopMetricsContext.Identified("node-x", LeaderIdSource.PROPERTY)
        LeaderRecorderContextDropLog.global().warnOnDrop(FakeRecorder::class, ctx)
        LeaderRecorderContextDropLog.global().droppedCount() shouldBeEqualTo 1L

        LeaderRecorderContextDropLog.setGlobal(LeaderRecorderContextDropLog())
        LeaderRecorderContextDropLog.global().droppedCount() shouldBeEqualTo 0L
    }

    @Test
    fun `Unknown is a data object singleton`() {
        LeaderAopMetricsContext.Unknown shouldBe LeaderAopMetricsContext.Unknown
    }

    @Test
    fun `Identified is not Unknown`() {
        val ctx = LeaderAopMetricsContext.Identified("x", LeaderIdSource.LITERAL)

        ctx.shouldNotBeInstanceOf<LeaderAopMetricsContext.Unknown>()
        ctx.shouldBeInstanceOf<LeaderAopMetricsContext.Identified>()
    }

    private class FakeRecorder
    private class AnotherFakeRecorder
    private class ThirdFakeRecorder

    private fun captureWarningMessages(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(LeaderRecorderContextDropLog::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
