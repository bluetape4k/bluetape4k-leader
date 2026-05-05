package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.spring.aop.metrics.SkipReason
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerLeaderAopMetricsRecorderTest {

    companion object : KLogging()

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var recorder: MicrometerLeaderAopMetricsRecorder

    private val lockName = "test-lock"
    private val defaultOptions = LeaderElectionOptions.Default

    @BeforeEach
    fun setup() {
        registry = SimpleMeterRegistry()
        recorder = MicrometerLeaderAopMetricsRecorder(registry)
    }

    @Test
    fun `onLockAttempt - attempts counter with lock name tag increments`() {
        recorder.onLockAttempt(lockName, defaultOptions)
        recorder.onLockAttempt(lockName, defaultOptions)

        val count = registry.get(MicrometerNames.METER_ATTEMPTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counter().count()

        count shouldBeEqualTo 2.0
    }

    @Test
    fun `onLockAcquired - acquired counter with lock name tag increments`() {
        recorder.onLockAcquired(lockName, defaultOptions, 10.milliseconds)

        val count = registry.get(MicrometerNames.METER_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counter().count()

        count shouldBeEqualTo 1.0
    }

    @Test
    fun `onLockNotAcquired CONTENTION - reason tag equals CONTENTION`() {
        recorder.onLockNotAcquired(lockName, defaultOptions, SkipReason.CONTENTION)

        val count = registry.get(MicrometerNames.METER_NOT_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .tag(MicrometerNames.TAG_REASON, SkipReason.CONTENTION.name)
            .counter().count()

        count shouldBeEqualTo 1.0
    }

    @Test
    fun `onLockNotAcquired BACKEND_ERROR - reason tag equals BACKEND_ERROR`() {
        recorder.onLockNotAcquired(lockName, defaultOptions, SkipReason.BACKEND_ERROR)

        val count = registry.get(MicrometerNames.METER_NOT_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .tag(MicrometerNames.TAG_REASON, SkipReason.BACKEND_ERROR.name)
            .counter().count()

        count shouldBeEqualTo 1.0
    }

    @Test
    fun `onTaskFinished - execution duration timer count increments`() {
        recorder.onTaskStarted(lockName)
        recorder.onTaskFinished(lockName, 100.milliseconds)

        val timerCount = registry.get(MicrometerNames.METER_EXECUTION_DURATION)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .timer().count()

        timerCount shouldBeGreaterOrEqualTo 1L
    }

    @Test
    fun `onTaskFailed - task failed counter with exception tag`() {
        recorder.onTaskFailed(lockName, 50.milliseconds, IllegalStateException("test"))

        val count = registry.get(MicrometerNames.METER_TASK_FAILED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .tag(MicrometerNames.TAG_EXCEPTION, "IllegalStateException")
            .counter().count()

        count shouldBeEqualTo 1.0
    }

    @Test
    fun `onTaskFailed without prior onTaskStarted - active gauge stays non-negative`() {
        // backend error path: onTaskFailed가 onTaskStarted 없이 호출될 수 있다
        recorder.onTaskFailed(lockName, 0.milliseconds, RuntimeException("backend error"))

        val gaugeValue = registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()?.value() ?: 0.0

        (gaugeValue >= 0.0).shouldBeTrue()
        gaugeValue shouldBeEqualTo 0.0
    }

    @Test
    fun `onTaskStarted - active gauge becomes 1`() {
        recorder.onTaskStarted(lockName)

        val gaugeValue = registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()?.value() ?: 0.0

        gaugeValue shouldBeEqualTo 1.0
    }

    @Test
    fun `onTaskStarted then onTaskFinished - active gauge returns to 0`() {
        recorder.onTaskStarted(lockName)
        recorder.onTaskFinished(lockName, 100.milliseconds)

        val gaugeValue = registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()?.value() ?: 0.0

        gaugeValue shouldBeEqualTo 0.0
    }

    @Test
    fun `onTaskStarted then onTaskFailed - active gauge returns to 0`() {
        recorder.onTaskStarted(lockName)
        recorder.onTaskFailed(lockName, 50.milliseconds, IllegalArgumentException("fail"))

        val gaugeValue = registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()?.value() ?: 0.0

        gaugeValue shouldBeEqualTo 0.0
    }

    @Test
    fun `registerMetricsFor - meters appear before first callback`() {
        recorder.registerMetricsFor(lockName)

        val attemptsCount = registry.get(MicrometerNames.METER_ATTEMPTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counter().count()
        val acquiredCount = registry.get(MicrometerNames.METER_ACQUIRED)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counter().count()
        val timerCount = registry.get(MicrometerNames.METER_EXECUTION_DURATION)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .timer().count()
        val gaugeValue = registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()?.value()

        attemptsCount shouldBeEqualTo 0.0
        acquiredCount shouldBeEqualTo 0.0
        timerCount shouldBeEqualTo 0L
        gaugeValue shouldBeEqualTo 0.0
    }

    @Test
    fun `registerMetricsFor - idempotent second call does not duplicate meters`() {
        recorder.registerMetricsFor(lockName)
        recorder.registerMetricsFor(lockName)

        // 중복 등록 없이 하나만 존재해야 한다
        val attemptCounters = registry.find(MicrometerNames.METER_ATTEMPTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .counters()

        attemptCounters.size shouldBeEqualTo 1
    }

    @Test
    fun `concurrent onTaskStarted and onTaskFinished - active gauge thread safe`() {
        runBlocking(Dispatchers.Default) {
            coroutineScope {
                repeat(1000) {
                    launch {
                        recorder.onTaskStarted(lockName)
                        recorder.onTaskFinished(lockName, 1.milliseconds)
                    }
                }
            }
        }

        val gaugeValue = registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge()?.value() ?: 0.0

        gaugeValue shouldBeEqualTo 0.0
    }

    @Test
    fun `deregisterMetricsFor - removes meters from registry`() {
        recorder.registerMetricsFor(lockName)

        // 등록 확인
        registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge().shouldNotBeNull()

        recorder.deregisterMetricsFor(lockName)

        // 제거 확인
        registry.find(MicrometerNames.METER_ACTIVE)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .gauge().shouldBeNull()
    }
}
