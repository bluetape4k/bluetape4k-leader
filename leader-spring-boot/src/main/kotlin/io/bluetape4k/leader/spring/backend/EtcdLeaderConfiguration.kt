package io.bluetape4k.leader.spring.backend

import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElector
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderGroupElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.adapter.PropertiesAdapter
import io.etcd.jetcd.Client
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * etcd backend auto-configuration.
 *
 * Activated only when a caller-owned jetcd [Client] bean is registered.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Client::class, EtcdLeaderElector::class)
@ConditionalOnBean(Client::class)
class EtcdLeaderConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["etcdLeaderElector"])
    fun etcdLeaderElector(
        client: Client,
        props: LeaderProperties,
    ): EtcdLeaderElector =
        EtcdLeaderElector(client, electionOptions(props))

    @Bean
    @ConditionalOnMissingBean(name = ["etcdSuspendLeaderElector"])
    fun etcdSuspendLeaderElector(
        client: Client,
        props: LeaderProperties,
    ): EtcdSuspendLeaderElector =
        EtcdSuspendLeaderElector(client, electionOptions(props))

    @Bean
    @ConditionalOnMissingBean(name = ["etcdLeaderGroupElector"])
    fun etcdLeaderGroupElector(
        client: Client,
        props: LeaderProperties,
    ): EtcdLeaderGroupElector =
        EtcdLeaderGroupElector(client, groupOptions(props))

    @Bean
    @ConditionalOnMissingBean(name = ["etcdSuspendLeaderGroupElector"])
    fun etcdSuspendLeaderGroupElector(
        client: Client,
        props: LeaderProperties,
    ): EtcdSuspendLeaderGroupElector =
        EtcdSuspendLeaderGroupElector(client, groupOptions(props))

    private fun electionOptions(props: LeaderProperties): EtcdLeaderElectionOptions =
        EtcdLeaderElectionOptions(
            leaderOptions = PropertiesAdapter.toCommonElection(props),
            keyPrefix = props.etcd.keyPrefix,
        )

    private fun groupOptions(props: LeaderProperties): EtcdLeaderGroupElectionOptions =
        EtcdLeaderGroupElectionOptions(
            leaderGroupOptions = PropertiesAdapter.toCommonGroup(props),
            keyPrefix = props.etcd.keyPrefix,
        )
}
