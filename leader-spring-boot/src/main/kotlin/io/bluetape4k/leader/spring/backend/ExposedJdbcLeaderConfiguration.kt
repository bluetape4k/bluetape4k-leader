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
 * Auto-configuration for the Exposed (JDBC) backend.
 *
 * Activated only when an `org.jetbrains.exposed.v1.jdbc.Database` bean is registered.
 *
 * Registered beans:
 * - `exposedJdbcLeaderElector` — synchronous single-leader elector
 * - `exposedJdbcLeaderGroupElector` — synchronous group-leader elector
 * - `exposedJdbcVirtualThreadLeaderElector` — Virtual Thread variant (wraps the sync bean)
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
