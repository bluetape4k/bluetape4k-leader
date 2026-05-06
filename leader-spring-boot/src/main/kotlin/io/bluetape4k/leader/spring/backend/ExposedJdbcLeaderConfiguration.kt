package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcVirtualThreadLeaderElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import org.jetbrains.exposed.v1.jdbc.Database
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Exposed(JDBC) 백엔드 자동 구성.
 *
 * `org.jetbrains.exposed.v1.jdbc.Database` 빈이 등록된 경우에만 활성화됩니다.
 *
 * 등록 빈:
 * - `exposedJdbcLeaderElector` — sync 단일 리더
 * - `exposedJdbcLeaderGroupElector` — sync 그룹 리더
 * - `exposedJdbcVirtualThreadLeaderElector` — Virtual Thread 변형 (sync 빈을 wrapping)
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
    ): ExposedJdbcLeaderElector =
        ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(leaderOptions = PropertiesAdapter.toCommonElection(props)),
        )

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcLeaderGroupElector"])
    fun exposedJdbcLeaderGroupElector(
        db: Database,
        props: LeaderProperties,
    ): ExposedJdbcLeaderGroupElector =
        ExposedJdbcLeaderGroupElector(
            db,
            ExposedJdbcLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props)),
        )

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcVirtualThreadLeaderElector"])
    fun exposedJdbcVirtualThreadLeaderElector(
        delegate: ExposedJdbcLeaderElector,
    ): ExposedJdbcVirtualThreadLeaderElector =
        ExposedJdbcVirtualThreadLeaderElector(delegate)
}
