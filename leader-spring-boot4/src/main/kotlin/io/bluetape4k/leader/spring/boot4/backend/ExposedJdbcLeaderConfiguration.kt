package io.bluetape4k.leader.spring.boot4.backend

import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElection
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElection
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcVirtualThreadLeaderElection
import io.bluetape4k.leader.spring.boot4.Boot4LeaderProperties
import io.bluetape4k.leader.spring.boot4.adapter.PropertiesAdapter
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
 * - `exposedJdbcLeaderElection` — sync 단일 리더
 * - `exposedJdbcLeaderGroupElection` — sync 그룹 리더
 * - `exposedJdbcVirtualThreadLeaderElection` — Virtual Thread 변형 (sync 빈을 wrapping)
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Database::class)
@ConditionalOnBean(Database::class)
class ExposedJdbcLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcLeaderElection"])
    fun exposedJdbcLeaderElection(
        db: Database,
        props: Boot4LeaderProperties,
    ): ExposedJdbcLeaderElection =
        ExposedJdbcLeaderElection(
            db,
            ExposedJdbcLeaderElectionOptions(leaderOptions = PropertiesAdapter.toCommonElection(props)),
        )

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcLeaderGroupElection"])
    fun exposedJdbcLeaderGroupElection(
        db: Database,
        props: Boot4LeaderProperties,
    ): ExposedJdbcLeaderGroupElection =
        ExposedJdbcLeaderGroupElection(
            db,
            ExposedJdbcLeaderGroupElectionOptions(leaderGroupOptions = PropertiesAdapter.toCommonGroup(props)),
        )

    @Bean
    @ConditionalOnMissingBean(name = ["exposedJdbcVirtualThreadLeaderElection"])
    fun exposedJdbcVirtualThreadLeaderElection(
        delegate: ExposedJdbcLeaderElection,
    ): ExposedJdbcVirtualThreadLeaderElection =
        ExposedJdbcVirtualThreadLeaderElection(delegate)
}
