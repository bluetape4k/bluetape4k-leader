package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

class LeaderScheduledTest {

    @Test
    fun `aliases Spring scheduling attributes`() {
        val scheduled = mergedAnnotation<Scheduled>()

        scheduled.cron shouldBeEqualTo "\${jobs.cron}"
        scheduled.zone shouldBeEqualTo "Asia/Seoul"
        scheduled.fixedRate shouldBeEqualTo 2L
        scheduled.fixedRateString shouldBeEqualTo "\${jobs.fixed-rate:}"
        scheduled.fixedDelay shouldBeEqualTo 3L
        scheduled.fixedDelayString shouldBeEqualTo "\${jobs.fixed-delay:}"
        scheduled.initialDelay shouldBeEqualTo 4L
        scheduled.initialDelayString shouldBeEqualTo "\${jobs.initial-delay:}"
        scheduled.timeUnit shouldBeEqualTo TimeUnit.SECONDS
        scheduled.scheduler shouldBeEqualTo "leaderScheduler"
    }

    @Test
    fun `aliases leader election attributes`() {
        val election = mergedAnnotation<LeaderElection>()

        election.name shouldBeEqualTo "\${jobs.lock-name}"
        election.waitTime shouldBeEqualTo "PT2S"
        election.leaseTime shouldBeEqualTo "PT30S"
        election.minLeaseTime shouldBeEqualTo "PT5S"
        election.autoExtend shouldBeEqualTo true
        election.streamBounded shouldBeEqualTo false
        election.bean shouldBeEqualTo "redisLeaderElectionFactory"
        election.failureMode shouldBeEqualTo LeaderAspectFailureMode.SKIP
    }

    private inline fun <reified A : Annotation> mergedAnnotation(): A =
        AnnotatedElementUtils.findMergedAnnotation(
            SampleJobs::class.java.getDeclaredMethod("run"),
            A::class.java,
        ).shouldNotBeNull()

    private class SampleJobs {
        @LeaderScheduled(
            name = "\${jobs.lock-name}",
            cron = "\${jobs.cron}",
            zone = "Asia/Seoul",
            fixedRate = 2,
            fixedRateString = "\${jobs.fixed-rate:}",
            fixedDelay = 3,
            fixedDelayString = "\${jobs.fixed-delay:}",
            initialDelay = 4,
            initialDelayString = "\${jobs.initial-delay:}",
            timeUnit = TimeUnit.SECONDS,
            scheduler = "leaderScheduler",
            waitTime = "PT2S",
            leaseTime = "PT30S",
            minLeaseTime = "PT5S",
            autoExtend = true,
            bean = "redisLeaderElectionFactory",
            failureMode = LeaderAspectFailureMode.SKIP,
        )
        fun run() = Unit
    }
}
