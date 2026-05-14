package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Auto-configuration for leader-history retention jobs.
 *
 * Loaded **after** [LeaderAopAutoConfiguration] so that `@LeaderElection` AOP weaving is
 * active before the retention jobs are created.
 *
 * Retention is enabled by default.  Set `bluetape4k.leader.history.retention.enabled=false`
 * to disable.
 *
 * Both [LeaderHistoryRetentionJob] (blocking) and [SuspendLeaderHistoryRetentionJob] (coroutine)
 * can run simultaneously when both JDBC and R2DBC / MongoDB sinks are present — they target
 * independent physical storage backends.
 */
@AutoConfiguration(after = [LeaderAopAutoConfiguration::class])
@ConditionalOnBean(LeaderElector::class)
@ConditionalOnProperty(prefix = "bluetape4k.leader.history.retention", name = ["enabled"], matchIfMissing = true)
@EnableScheduling
class LeaderHistoryRetentionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LeaderHistoryRetentionJob::class)
    @ConditionalOnBean(LeaderHistorySink::class)
    fun leaderHistoryRetentionJob(
        sink: LeaderHistorySink,
        @Value("\${bluetape4k.leader.history.retention.days:30}") retentionDays: Long,
        @Value("\${bluetape4k.leader.history.retention.chunk-size:1000}") chunkSize: Int,
        @Value("\${bluetape4k.leader.history.retention.max-duration-ms:300000}") maxDurationMs: Long,
    ): LeaderHistoryRetentionJob =
        LeaderHistoryRetentionJob(sink, retentionDays, chunkSize, maxDurationMs)

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderHistoryRetentionJob::class)
    @ConditionalOnBean(SuspendLeaderHistorySink::class)
    fun suspendLeaderHistoryRetentionJob(
        sink: SuspendLeaderHistorySink,
        @Value("\${bluetape4k.leader.history.retention.days:30}") retentionDays: Long,
        @Value("\${bluetape4k.leader.history.retention.chunk-size:1000}") chunkSize: Int,
        @Value("\${bluetape4k.leader.history.retention.max-duration-ms:300000}") maxDurationMs: Long,
    ): SuspendLeaderHistoryRetentionJob =
        SuspendLeaderHistoryRetentionJob(sink, retentionDays, chunkSize, maxDurationMs)
}
