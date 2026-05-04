package io.bluetape4k.leader.spring.boot3.backend

import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcSuspendLeaderElection
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcSuspendLeaderGroupElection
import io.bluetape4k.leader.spring.boot3.Boot3LeaderProperties
import io.bluetape4k.leader.spring.boot3.adapter.PropertiesAdapter
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
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
    @ConditionalOnMissingBean(name = ["exposedR2dbcSuspendLeaderElection"])
    fun exposedR2dbcSuspendLeaderElection(
        db: R2dbcDatabase,
        props: Boot3LeaderProperties,
    ): ExposedR2dbcSuspendLeaderElection = runBlocking {
        ExposedR2dbcSuspendLeaderElection(
            db,
            ExposedR2dbcLeaderElectionOptions(leaderOptions = PropertiesAdapter.toCommonElection(props)),
        )
    }

    @Bean
    @ConditionalOnMissingBean(name = ["exposedR2dbcSuspendLeaderGroupElection"])
    fun exposedR2dbcSuspendLeaderGroupElection(
        db: R2dbcDatabase,
        props: Boot3LeaderProperties,
    ): ExposedR2dbcSuspendLeaderGroupElection = runBlocking {
        ExposedR2dbcSuspendLeaderGroupElection(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props)),
        )
    }
}
