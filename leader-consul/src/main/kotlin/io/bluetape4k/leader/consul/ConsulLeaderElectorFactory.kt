package io.bluetape4k.leader.consul

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory

/**
 * `ConsulLeaderElectorFactory`는 endpoint와 기본 옵션으로 Consul 단일 elector를 생성합니다.
 *
 * @property endpoint Consul API base URI와 optional datacenter/ACL token/HTTP timeout입니다.
 * @property baseOptions create 때 leader 옵션만 교체해 사용할 기본 단일 설정입니다.
 */
class ConsulLeaderElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ConsulLeaderElector(
            endpoint,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `ConsulLeaderGroupElectorFactory`는 endpoint와 그룹 기본 옵션으로 Consul slot elector를 생성합니다.
 *
 * @property endpoint Consul API base URI와 optional datacenter/ACL token/HTTP timeout입니다.
 * @property baseOptions create 때 그룹 옵션만 교체해 사용할 기본 그룹 설정입니다.
 */
class ConsulLeaderGroupElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ConsulLeaderGroupElector(
            endpoint,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

/**
 * `ConsulSuspendLeaderElectorFactory`는 endpoint와 기본 옵션으로 Consul suspend 단일 elector를 생성합니다.
 *
 * @property endpoint suspend Consul API 호출에 공유할 endpoint 설정입니다.
 * @property baseOptions create 때 leader 옵션만 교체해 사용할 기본 단일 설정입니다.
 */
class ConsulSuspendLeaderElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ConsulSuspendLeaderElector(
            endpoint,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `ConsulSuspendLeaderGroupElectorFactory`는 endpoint와 그룹 기본 옵션으로 Consul suspend slot elector를 생성합니다.
 *
 * @property endpoint suspend Consul API 호출에 공유할 endpoint 설정입니다.
 * @property baseOptions create 때 그룹 옵션만 교체해 사용할 기본 그룹 설정입니다.
 */
class ConsulSuspendLeaderGroupElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(
            endpoint,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
