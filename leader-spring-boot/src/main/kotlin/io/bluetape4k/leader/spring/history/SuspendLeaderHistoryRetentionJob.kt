package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Coroutine-based retention job for R2DBC and MongoDB backends.
 *
 * Uses a deferred [Mono] bridge so that `@LeaderElection` (Mono return-type path, Issue #91)
 * holds the lock until the coroutine completes, preventing fire-and-forget split-brain.
 *
 * JDBC (blocking) cleanup is handled by [LeaderHistoryRetentionJob].  Both jobs run
 * independently because they target different physical storage backends.
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
class SuspendLeaderHistoryRetentionJob(
    private val sink: SuspendLeaderHistorySink,
    @Value("\${bluetape4k.leader.history.retention.days:30}")
    private val retentionDays: Long = 30L,
    @Value("\${bluetape4k.leader.history.retention.chunk-size:1000}")
    private val chunkSize: Int = 1000,
    @Value("\${bluetape4k.leader.history.retention.max-duration-ms:300000}")
    private val maxDurationMs: Long = 300_000L,
) : InitializingBean {
    companion object : KLogging()

    override fun afterPropertiesSet() {
        if (sink === NoopSuspendLeaderHistorySink) {
            log.warn { "Suspend retention is enabled but sink is Noop — no rows will be deleted." }
        }
    }

    @Scheduled(cron = "\${bluetape4k.leader.history.retention.cron:0 0 2 * * ?}")
    fun runRetention() {
        runRetentionGuarded().block()
    }

    @LeaderElection("bluetape4k-leader-history-retention-suspend", autoExtend = true)
    fun runRetentionGuarded(): Mono<Void> =
        mono(Dispatchers.IO) { runRetentionInternal() }.then()

    private suspend fun runRetentionInternal() {
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val deadline = System.currentTimeMillis() + maxDurationMs
        var deleted: Int
        do {
            deleted = sink.deleteOlderThan(cutoff, chunkSize)
        } while (deleted >= chunkSize && System.currentTimeMillis() < deadline)

        if (System.currentTimeMillis() >= deadline) {
            log.warn { "Suspend retention loop exceeded budget; remaining rows deferred." }
        }
    }
}
