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
 * `ConsulLeaderElectorFactory`는 Consul backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property endpoint Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ConsulLeaderElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ConsulLeaderElector(
            endpoint,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `ConsulLeaderGroupElectorFactory`는 Consul backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property endpoint Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ConsulLeaderGroupElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ConsulLeaderGroupElector(
            endpoint,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

/**
 * `ConsulSuspendLeaderElectorFactory`는 Consul backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property endpoint Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ConsulSuspendLeaderElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ConsulSuspendLeaderElector(
            endpoint,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `ConsulSuspendLeaderGroupElectorFactory`는 Consul backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property endpoint Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions Consul backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ConsulSuspendLeaderGroupElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(
            endpoint,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
