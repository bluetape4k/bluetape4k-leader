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
 * 배치 스케줄러 예제.
 *
 * ## 동작/계약
 *
 * 다중 인스턴스 배포 환경에서 동일 lock 이름으로 [LettuceLeaderElector] 를 공유하면,
 * 매 실행 시점에 단 한 인스턴스만 [run] 의 action 을 수행한다.
 *
 * - 리더 선출 실패 시 [run] 은 `null` 반환 — throw 하지 않음 (ShedLock 호환 동작)
 * - [waitTime] 동안 락 획득 시도 후 실패하면 즉시 skip
 * - [leaseTime] 동안 다른 인스턴스의 락 획득을 차단
 * - [nodeId] 는 로그 식별 용도 — 락 키나 락 소유자 식별에는 사용되지 않음
 *   (락 소유자 ID 는 [LettuceLeaderElector] 가 자체 발급)
 *
 * ```kotlin
 * val scheduler = BatchScheduler(
 *     nodeId = "node-A",
 *     connection = redisConnection,
 *     lockName = "nightly-settlement",
 * )
 * val executed = scheduler.run { settlementJob.execute() }
 * if (executed != null) println("리더로 실행 완료") else println("다른 인스턴스 실행 중")
 * ```
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
     * 리더 선출 성공 시 [job] 을 1회 실행하고 반환값을 돌려준다.
     *
     * @return 리더로 실행 시 [job] 의 반환값, 실패 시 `null`
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
