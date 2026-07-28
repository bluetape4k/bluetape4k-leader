package io.bluetape4k.leader.examples.batch

import io.bluetape4k.leader.LeaderElectionOptions
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
     * `선언` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun <T> run(job: () -> T): T? {
        return elector.runIfLeader(lockName) {
            log.info { "[$nodeId] 리더 선출 성공 — Job 실행 시작" }
            val result = job()
            log.info { "[$nodeId] Job 실행 완료" }
            result
        }.also {
            if (it == null) {
                log.info { "[$nodeId] 리더 선출 실패 — 다른 인스턴스가 실행 중. skip." }
            }
        }
    }
}
