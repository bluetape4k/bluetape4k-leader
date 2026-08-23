package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import java.io.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import kotlin.time.toKotlinDuration

/**
 * 최근 backend 획득 실패 관찰 결과입니다.
 *
 * 개별 lock 이름은 보존하지 않고 bounded aggregate만 외부에 노출합니다.
 */
data class LeaderAcquisitionFailureView(
    val count: Int,
    val lastFailureAt: Instant?,
    val window: Duration,
    val capacity: Int,
    val overflowed: Boolean,
) : Serializable {

    companion object {
        val DefaultWindow: Duration = Duration.ofMinutes(5)
        const val DefaultCapacity: Int = 1024

        fun empty(
            window: Duration = DefaultWindow,
            capacity: Int = DefaultCapacity,
        ): LeaderAcquisitionFailureView = LeaderAcquisitionFailureView(
            count = 0,
            lastFailureAt = null,
            window = window,
            capacity = capacity,
            overflowed = false,
        )

        private const val serialVersionUID = 1L
    }
}

/**
 * backend 획득 실패를 bounded timestamp window로 기록하는 internal recorder입니다.
 */
internal class LeaderAcquisitionFailureWindow(
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val capacity: Int = LeaderAcquisitionFailureView.DefaultCapacity,
) : LeaderAopMetricsRecorder {

    internal companion object {
        val DefaultWindow: Duration = LeaderAcquisitionFailureView.DefaultWindow
        const val DefaultCapacity: Int = LeaderAcquisitionFailureView.DefaultCapacity
    }

    private val monitor = Any()
    private val failures = ArrayDeque<Instant>(capacity)
    private var overflowed = false

    init {
        val kotlinWindow = window.toKotlinDuration()
        require(kotlinWindow.isFinite() && kotlinWindow.isPositive()) {
            "acquisition failure window must be positive and finite: $window"
        }
        require(capacity > 0) {
            "acquisition failure window capacity must be positive: $capacity"
        }
    }

    override fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {
        if (reason != SkipReason.BACKEND_ERROR) return

        runCatching {
            val failureAt = clock.instant()
            synchronized(monitor) {
                failures.addLast(failureAt)
                while (failures.size > capacity) {
                    failures.removeFirst()
                    overflowed = true
                }
            }
        }
    }

    fun view(now: Instant = clock.instant()): LeaderAcquisitionFailureView = synchronized(monitor) {
        val boundary = now.minus(window)
        val iterator = failures.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().isBefore(boundary)) {
                iterator.remove()
            }
        }
        if (failures.isEmpty()) {
            overflowed = false
        }

        LeaderAcquisitionFailureView(
            count = failures.size,
            lastFailureAt = failures.maxOrNull(),
            window = window,
            capacity = capacity,
            overflowed = overflowed,
        )
    }
}
