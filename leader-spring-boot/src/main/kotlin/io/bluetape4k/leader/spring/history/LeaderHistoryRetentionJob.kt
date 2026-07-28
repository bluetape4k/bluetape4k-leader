package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.spring.scheduling.LeaderScheduled
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `LeaderHistoryRetentionJob`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property sink Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property retentionDays Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property chunkSize Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property maxDurationMs Spring Boot integration 계약에서 사용하는 속성입니다.
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

    @LeaderScheduled(
        name = "bluetape4k-leader-history-retention",
        cron = "\${bluetape4k.leader.history.retention.cron:0 0 2 * * ?}",
        autoExtend = true,
    )
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
