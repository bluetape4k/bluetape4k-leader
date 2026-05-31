package io.bluetape4k.leader.examples.redissonwatchdog

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import org.redisson.api.RedissonClient
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a leader-only job with the Redisson backend and bluetape4k lease auto-extension.
 *
 * ## Contract
 *
 * A runner calls [RedissonLeaderElector.runIfLeader] with `autoExtend=true`.
 * The initial Redisson lock uses an explicit lease time, and bluetape4k's
 * `LeaderLeaseAutoExtender` renews that lease while [runJob] is still running.
 * Contending nodes return [RedissonWatchdogStatus.SKIPPED] instead of throwing.
 *
 * ```kotlin
 * val runner = RedissonWatchdogJobRunner("node-a", redissonClient, "report-rollup")
 * val report = runner.runJob {
 *     reportService.rollup()
 * }
 * ```
 */
class RedissonWatchdogJobRunner(
    val nodeId: String,
    redissonClient: RedissonClient,
    private val lockName: String,
    options: LeaderElectionOptions = watchdogOptions(),
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object: KLogging() {
        fun watchdogOptions(
            waitTime: Duration = 100.milliseconds,
            leaseTime: Duration = 750.milliseconds,
        ): LeaderElectionOptions =
            LeaderElectionOptions(
                waitTime = waitTime,
                leaseTime = leaseTime,
                nodeId = "redisson-watchdog-runner",
                autoExtend = true,
            )
    }

    private val elector = RedissonLeaderElector(redissonClient, options)

    /**
     * Executes [job] only when this runner acquires leadership.
     *
     * @return a report describing whether this node executed or skipped the job
     */
    fun runJob(job: () -> Unit): RedissonWatchdogNodeReport {
        var elected = false
        var jobThreadName: String? = null
        val startedAt = System.nanoTime()

        elector.runIfLeader(lockName) {
            elected = true
            jobThreadName = Thread.currentThread().name
            log.info { "[$nodeId] acquired leader lock $lockName" }
            job()
            log.info { "[$nodeId] completed leader-only job $lockName" }
        }

        val elapsed = (System.nanoTime() - startedAt).nanoseconds
        return RedissonWatchdogNodeReport(
            nodeId = nodeId,
            status = if (elected) RedissonWatchdogStatus.ELECTED else RedissonWatchdogStatus.SKIPPED,
            elapsed = elapsed,
            jobThreadName = jobThreadName,
        )
    }
}

enum class RedissonWatchdogStatus {
    ELECTED,
    SKIPPED,
}

data class RedissonWatchdogNodeReport(
    val nodeId: String,
    val status: RedissonWatchdogStatus,
    val elapsed: Duration,
    val jobThreadName: String?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
