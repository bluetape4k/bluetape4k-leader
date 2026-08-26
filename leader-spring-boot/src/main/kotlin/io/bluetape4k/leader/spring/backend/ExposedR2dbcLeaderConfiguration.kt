package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `ExposedR2dbcLeaderConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(R2dbcDatabase::class)
@ConditionalOnBean(R2dbcDatabase::class)
class ExposedR2dbcLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["exposedR2dbcSuspendLeaderElector"])
    fun exposedR2dbcSuspendLeaderElector(
        db: R2dbcDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SuspendSafeLeaderHistoryRecorder>,
    ): ExposedR2DbcSuspendLeaderElector = createSuspendBackendBean(
        operationName = "exposedR2dbcSuspendLeaderElector",
    ) {
        ExposedR2DbcSuspendLeaderElector(
            db,
            ExposedR2dbcLeaderElectionOptions(leaderOptions = PropertiesAdapter.toCommonElection(props)),
            recorderProvider.ifAvailable,
        )
    }

    @Bean
    @ConditionalOnMissingBean(name = ["exposedR2dbcSuspendLeaderGroupElector"])
    fun exposedR2dbcSuspendLeaderGroupElector(
        db: R2dbcDatabase,
        props: LeaderProperties,
        recorderProvider: ObjectProvider<SuspendSafeLeaderHistoryRecorder>,
    ): ExposedR2DbcSuspendLeaderGroupElector = createSuspendBackendBean(
        operationName = "exposedR2dbcSuspendLeaderGroupElector",
    ) {
        ExposedR2DbcSuspendLeaderGroupElector(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props)),
            recorderProvider.ifAvailable,
        )
    }
}
