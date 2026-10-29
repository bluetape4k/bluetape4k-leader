package io.bluetape4k.leader.spring.history

import io.bluetape4k.javatimes.days
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import reactor.core.publisher.Mono
import java.time.Instant

/** scheduled coroutine leader task로 오래된 suspend history row를 chunk 단위로 삭제합니다.
 *
 * @property sink 삭제할 suspend history row를 제공하는 sink입니다.
 * @property retentionDays 현재 시각에서 이 일수보다 오래된 row를 삭제하며 기본값은 30일입니다.
 * @property chunkSize 한 번의 sink 호출에서 삭제할 최대 row 수이며 기본값은 1000입니다.
 * @property maxDurationMs 한 실행에서 cleanup loop가 사용할 최대 시간이며 기본값은 300000ms입니다.
 */
class SuspendLeaderHistoryRetentionJob(
    private val sink: SuspendLeaderHistorySink,
    @Value($$"${bluetape4k.leader.history.retention.days:30}")
    private val retentionDays: Long = 30L,
    @Value($$"${bluetape4k.leader.history.retention.chunk-size:1000}")
    private val chunkSize: Int = 1000,
    @Value($$"${bluetape4k.leader.history.retention.max-duration-ms:300000}")
    private val maxDurationMs: Long = 300_000L,
): InitializingBean {
    companion object: KLogging()

    override fun afterPropertiesSet() {
        if (sink === NoopSuspendLeaderHistorySink) {
            log.warn { "Suspend retention is enabled but sink is Noop — no rows will be deleted." }
        }
    }

    @Scheduled(cron = $$"${bluetape4k.leader.history.retention.cron:0 0 2 * * ?}")
    fun runRetention() {
        runRetentionGuarded().block()
    }

    @LeaderElection("bluetape4k-leader-history-retention-suspend", autoExtend = true)
    fun runRetentionGuarded(): Mono<Void> =
        mono(Dispatchers.IO) { runRetentionInternal() }.then()

    private suspend fun runRetentionInternal() {
        val cutoff = Instant.now() - retentionDays.days()
        val deadline = System.currentTimeMillis() + maxDurationMs
        var deleted: Int

        do {
            log.debug { "delete older than $cutoff, chunkSize=$chunkSize" }
            deleted = sink.deleteOlderThan(cutoff, chunkSize)
        } while (deleted >= chunkSize && System.currentTimeMillis() < deadline)

        if (System.currentTimeMillis() >= deadline) {
            log.warn { "Suspend retention loop exceeded budget; remaining rows deferred." }
        }
    }
}
