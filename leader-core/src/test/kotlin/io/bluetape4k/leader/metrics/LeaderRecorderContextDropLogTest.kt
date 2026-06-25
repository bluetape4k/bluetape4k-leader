package io.bluetape4k.leader.metrics

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeInstanceOf
import io.bluetape4k.leader.identity.LeaderIdSource
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

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
    fun `Empty sentinel is Unknown - no drop counted`() {
        val ctx = LeaderAopMetricsContext.Empty
        (ctx is LeaderAopMetricsContext.Unknown).shouldBeTrue()
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
        (LeaderRecorderContextDropLog.global() === fresh).shouldBeTrue()
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
        (LeaderAopMetricsContext.Unknown === LeaderAopMetricsContext.Unknown).shouldBeTrue()
    }

    @Test
    fun `Identified is not Unknown`() {
        val ctx = LeaderAopMetricsContext.Identified("x", LeaderIdSource.LITERAL)

        ctx shouldNotBeInstanceOf LeaderAopMetricsContext.Unknown::class
        ctx shouldBeInstanceOf LeaderAopMetricsContext.Identified::class
    }

    private class FakeRecorder
    private class AnotherFakeRecorder
}
