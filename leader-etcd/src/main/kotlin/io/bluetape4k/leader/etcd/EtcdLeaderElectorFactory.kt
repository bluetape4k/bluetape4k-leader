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
 * `EtcdLeaderElectorFactory`는 jetcd client와 기본 옵션으로 단일 blocking elector를 생성합니다.
 *
 * @property client lease와 lock API를 호출할 jetcd client입니다.
 * @property baseOptions create 호출에 복사해 쓰는 기본 단일 선거 설정입니다.
 */
class EtcdLeaderElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        EtcdLeaderElector(
            client,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `EtcdLeaderGroupElectorFactory`는 jetcd client와 그룹 기본 옵션으로 slot elector를 생성합니다.
 *
 * @property client lease와 group slot lock API를 호출할 jetcd client입니다.
 * @property baseOptions create 호출에 복사해 쓰는 기본 그룹 선거 설정입니다.
 */
class EtcdLeaderGroupElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        EtcdLeaderGroupElector(
            client,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

/**
 * `EtcdSuspendLeaderElectorFactory`는 jetcd client와 기본 옵션으로 suspend 단일 elector를 생성합니다.
 *
 * @property client suspend lease와 lock API를 호출할 jetcd client입니다.
 * @property baseOptions create 호출에 복사해 쓰는 기본 단일 선거 설정입니다.
 */
class EtcdSuspendLeaderElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        EtcdSuspendLeaderElector(
            client,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * `EtcdSuspendLeaderGroupElectorFactory`는 jetcd client와 그룹 기본 옵션으로 suspend slot elector를 생성합니다.
 *
 * @property client suspend group slot lock API를 호출할 jetcd client입니다.
 * @property baseOptions create 호출에 복사해 쓰는 기본 그룹 선거 설정입니다.
 */
class EtcdSuspendLeaderGroupElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
): SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        EtcdSuspendLeaderGroupElector(
            client,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
