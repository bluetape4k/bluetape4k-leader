package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcVirtualThreadLeaderElector
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `ExposedJdbcLeaderConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Database::class)
@ConditionalOnBean(Database::class)
class ExposedJdbcLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcLeaderElector"])
    fun exposedJdbcLeaderElector(
        db: Database,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SafeLeaderHistoryRecorder>,
    ): ExposedJdbcLeaderElector =
        ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(leaderOptions = PropertiesAdapter.toCommonElection(props)),
            recorderProvider.ifAvailable,
        )

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcLeaderGroupElector"])
    fun exposedJdbcLeaderGroupElector(
        db: Database,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SafeLeaderHistoryRecorder>,
    ): ExposedJdbcLeaderGroupElector =
        ExposedJdbcLeaderGroupElector(
            db,
            ExposedJdbcLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props)),
            recorderProvider.ifAvailable,
        )

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcVirtualThreadLeaderElector"])
    fun exposedJdbcVirtualThreadLeaderElector(
        delegate: ExposedJdbcLeaderElector,
    ): ExposedJdbcVirtualThreadLeaderElector =
        ExposedJdbcVirtualThreadLeaderElector(delegate)
}
