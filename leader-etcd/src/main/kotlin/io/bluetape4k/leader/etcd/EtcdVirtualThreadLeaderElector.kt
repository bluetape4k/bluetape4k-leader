package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.etcd.jetcd.Client

/**
 * `EtcdVirtualThreadLeaderElector`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property delegate etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class EtcdVirtualThreadLeaderElector(
    private val delegate: EtcdLeaderElector,
) : VirtualThreadLeaderElector,
    LeaderBackendDiagnosticsProvider by EtcdLeaderBackendDiagnostics {

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}

fun <T> Client.runVirtualIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    EtcdVirtualThreadLeaderElector(EtcdLeaderElector(this, options)).runAsyncIfLeader(lockName, action)
