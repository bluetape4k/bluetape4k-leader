package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoOpLeaderAopMetricsRecorderTest {

    companion object : KLogging()

    private val recorder: LeaderAopMetricsRecorder = LeaderAopMetricsRecorder.NoOp
    private val options = LeaderElectionOptions()

    @Test
    fun `NoOp은 LeaderAopMetricsRecorder 구현체`() {
        recorder.shouldBeInstanceOf<LeaderAopMetricsRecorder>()
    }

    @Test
    fun `onLockAttempt 예외 없이 호출`() {
        recorder.onLockAttempt("test-lock", options)
    }

    @Test
    fun `onLockAcquired 예외 없이 호출`() {
        recorder.onLockAcquired("test-lock", options, 10.seconds)
    }

    @Test
    fun `onLockNotAcquired CONTENTION 예외 없이 호출`() {
        recorder.onLockNotAcquired("test-lock", options, SkipReason.CONTENTION)
    }

    @Test
    fun `onLockNotAcquired BACKEND_ERROR 예외 없이 호출`() {
        recorder.onLockNotAcquired("test-lock", options, SkipReason.BACKEND_ERROR)
    }

    @Test
    fun `onTaskStarted 예외 없이 호출`() {
        recorder.onTaskStarted("test-lock")
    }

    @Test
    fun `onTaskFinished 예외 없이 호출`() {
        recorder.onTaskFinished("test-lock", 100.seconds)
    }

    @Test
    fun `onTaskFailed 예외 없이 호출`() {
        recorder.onTaskFailed("test-lock", 5.seconds, RuntimeException("test"))
    }
}
