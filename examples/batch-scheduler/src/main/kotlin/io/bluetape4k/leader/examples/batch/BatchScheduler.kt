package io.bluetape4k.leader.examples.batch

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `BatchScheduler`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property nodeId example workflow 계약에서 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 사용하는 속성입니다.
 */
class BatchScheduler(
    val nodeId: String,
    connection: StatefulRedisConnection<String, String>,
    private val lockName: String,
    waitTime: Duration = 2.seconds,
    leaseTime: Duration = 30.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object: KLogging()

    private val elector = LettuceLeaderElector(
        connection,
        LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime),
    )

    /**
     * 리더로 선출되면 작업을 실행하며 실제 경합일 때만 skip 로그를 남깁니다.
     *
     * 반환형은 T?이므로 작업의 null 결과와 경합을 반환값만으로 구분할 수 없습니다.
     * 작업 오류·취소는 원본을 전파하며 InterruptedException의 interrupt flag를 복원합니다.
     */
    fun <T> run(job: () -> T): T? {
        val outcome = elector.runIfLeaderResult(lockName) {
            log.info { "[$nodeId] 리더 선출 성공 — Job 실행 시작" }
            val result = job()
            log.info { "[$nodeId] Job 실행 완료" }
            result
        }
        return when (outcome) {
            is LeaderRunResult.Elected -> outcome.value
            LeaderRunResult.Skipped -> {
                log.info { "[$nodeId] 리더 선출 실패 — 다른 인스턴스가 실행 중. skip." }
                null
            }
            is LeaderRunResult.ActionFailed -> throw outcome.cause
        }
    }
}
