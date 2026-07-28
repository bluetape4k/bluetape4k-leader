package io.bluetape4k.leader.etcd

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
import io.etcd.jetcd.Client

/**
 * `EtcdLeaderElectorFactory`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class EtcdLeaderElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        EtcdLeaderElector(
            client,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `EtcdLeaderGroupElectorFactory`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class EtcdLeaderGroupElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        EtcdLeaderGroupElector(
            client,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

/**
 * `EtcdSuspendLeaderElectorFactory`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class EtcdSuspendLeaderElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        EtcdSuspendLeaderElector(
            client,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `EtcdSuspendLeaderGroupElectorFactory`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property baseOptions etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class EtcdSuspendLeaderGroupElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        EtcdSuspendLeaderGroupElector(
            client,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
