package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

/** history retention property와 elector/sink bean이 있을 때 scheduled cleanup job을 등록합니다. */
@AutoConfiguration(after = [LeaderAopAutoConfiguration::class])
@ConditionalOnProperty(prefix = "bluetape4k.leader.history.retention", name = ["enabled"], matchIfMissing = true)
@EnableScheduling
class LeaderHistoryRetentionAutoConfiguration {

    companion object: KLogging()

    @Bean
    @ConditionalOnMissingBean(LeaderHistoryRetentionJob::class)
    @ConditionalOnBean(LeaderElectorFactory::class, LeaderHistorySink::class)
    fun leaderHistoryRetentionJob(
        sink: LeaderHistorySink,
        @Value($$"${bluetape4k.leader.history.retention.days:30}") retentionDays: Long,
        @Value($$"${bluetape4k.leader.history.retention.chunk-size:1000}") chunkSize: Int,
        @Value($$"${bluetape4k.leader.history.retention.max-duration-ms:300000}") maxDurationMs: Long,
    ): LeaderHistoryRetentionJob =
        LeaderHistoryRetentionJob(sink, retentionDays, chunkSize, maxDurationMs)

    @Bean
    @ConditionalOnMissingBean(SuspendLeaderHistoryRetentionJob::class)
    @ConditionalOnBean(
        LeaderElectorFactory::class,
        SuspendLeaderElectorFactory::class,
        SuspendLeaderHistorySink::class,
    )
    fun suspendLeaderHistoryRetentionJob(
        sink: SuspendLeaderHistorySink,
        @Value($$"${bluetape4k.leader.history.retention.days:30}") retentionDays: Long,
        @Value($$"${bluetape4k.leader.history.retention.chunk-size:1000}") chunkSize: Int,
        @Value($$"${bluetape4k.leader.history.retention.max-duration-ms:300000}") maxDurationMs: Long,
    ): SuspendLeaderHistoryRetentionJob =
        SuspendLeaderHistoryRetentionJob(sink, retentionDays, chunkSize, maxDurationMs)
}
