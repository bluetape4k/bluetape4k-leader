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
 * `RedissonWatchdogJobRunner`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 사용하는 속성입니다.
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
     * `runJob` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

/**
 * `RedissonWatchdogNodeReport`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property elapsed example workflow 계약에서 `elapsed` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property jobThreadName example workflow 계약에서 `jobThreadName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
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
