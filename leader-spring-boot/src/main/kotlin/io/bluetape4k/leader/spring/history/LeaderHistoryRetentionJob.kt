package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Periodic retention job that deletes expired leader-lock history records.
 *
 * ## Behavior / Contract
 * - Runs on the cron schedule defined by `bluetape4k.leader.history.retention.cron`
 *   (default: `0 0 2 * * ?` — every day at 02:00).
 * - Deletes records older than `bluetape4k.leader.history.retention.days` days (default 30).
 * - Processes at most `bluetape4k.leader.history.retention.chunk-size` rows per batch
 *   (default 1000) to avoid long-running transactions.
 * - Bounded by a wall-clock budget: `bluetape4k.leader.history.retention.max-duration-ms`
 *   (default 300000 ms = 5 minutes).  If the budget is exceeded, remaining rows are deferred
 *   to the next scheduled run and a WARN is logged.
 * - Decorated with `@LeaderElection` to prevent concurrent execution across multiple pods
 *   (dogfooding this library's own AOP).
 *
 * ## Configuration example
 * ```yaml
 * bluetape4k.leader.history.retention:
 *   enabled: true
 *   cron: "0 0 2 * * ?"
 *   days: 30
 *   chunk-size: 1000
 *   max-duration-ms: 300000
 * ```
 */
class LeaderHistoryRetentionJob(
    private val sink: LeaderHistorySink,
    @Value("\${bluetape4k.leader.history.retention.days:30}")
    private val retentionDays: Long = 30L,
    @Value("\${bluetape4k.leader.history.retention.chunk-size:1000}")
    private val chunkSize: Int = 1000,
    @Value("\${bluetape4k.leader.history.retention.max-duration-ms:300000}")
    private val maxDurationMs: Long = 300_000L,
) : InitializingBean {
    companion object : KLogging()

    override fun afterPropertiesSet() {
        if (sink === NoopLeaderHistorySink) {
            log.warn { "Retention is enabled but sink is Noop — no rows will be deleted." }
        }
    }

    @Scheduled(cron = "\${bluetape4k.leader.history.retention.cron:0 0 2 * * ?}")
    @LeaderElection("bluetape4k-leader-history-retention")
    fun runRetention() {
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val deadline = System.currentTimeMillis() + maxDurationMs
        var deleted: Int
        do {
            deleted = sink.deleteOlderThan(cutoff, chunkSize)
        } while (deleted >= chunkSize && System.currentTimeMillis() < deadline)

        if (System.currentTimeMillis() >= deadline) {
            log.warn { "Retention loop exceeded budget; remaining rows deferred." }
        }
    }
}
