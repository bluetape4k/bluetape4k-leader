package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Exposed(R2DBC) 백엔드 자동 구성.
 *
 * `R2dbcDatabase` 빈이 등록된 경우에만 활성화됩니다.
 *
 * 두 빈 모두 startup 시 스키마 초기화를 위해 [runBlocking]을 사용합니다.
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
    ): ExposedR2DbcSuspendLeaderElector = runBlocking {
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
    ): ExposedR2DbcSuspendLeaderGroupElector = runBlocking {
        ExposedR2DbcSuspendLeaderGroupElector(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props)),
            recorderProvider.ifAvailable,
        )
    }
}
