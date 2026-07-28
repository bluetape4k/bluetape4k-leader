package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
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
 * `LeaderHistoryRetentionAutoConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@AutoConfiguration(after = [LeaderAopAutoConfiguration::class])
@ConditionalOnProperty(prefix = "bluetape4k.leader.history.retention", name = ["enabled"], matchIfMissing = true)
@EnableScheduling
class LeaderHistoryRetentionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LeaderHistoryRetentionJob::class)
    @ConditionalOnBean(LeaderElectorFactory::class, LeaderHistorySink::class)
    fun leaderHistoryRetentionJob(
        sink: LeaderHistorySink,
        @Value("\${bluetape4k.leader.history.retention.days:30}") retentionDays: Long,
        @Value("\${bluetape4k.leader.history.retention.chunk-size:1000}") chunkSize: Int,
        @Value("\${bluetape4k.leader.history.retention.max-duration-ms:300000}") maxDurationMs: Long,
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
        @Value("\${bluetape4k.leader.history.retention.days:30}") retentionDays: Long,
        @Value("\${bluetape4k.leader.history.retention.chunk-size:1000}") chunkSize: Int,
        @Value("\${bluetape4k.leader.history.retention.max-duration-ms:300000}") maxDurationMs: Long,
    ): SuspendLeaderHistoryRetentionJob =
        SuspendLeaderHistoryRetentionJob(sink, retentionDays, chunkSize, maxDurationMs)
}
