package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.etcd.jetcd.Client
import io.bluetape4k.leader.etcd.runVirtualIfLeader as currentRunVirtualIfLeader

/**
 * `EtcdVirtualThreadLeaderElector`는 etcd blocking elector 호출을 virtual thread에서 실행합니다.
 *
 * @property delegate blocking etcd elector에 lease 획득·작업·반납 처리를 위임하는 객체입니다.
 */
class EtcdVirtualThreadLeaderElector(
    private val delegate: EtcdLeaderElector,
): VirtualThreadLeaderElector,
   LeaderBackendDiagnosticsProvider by EtcdLeaderBackendDiagnostics {

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(lockName, action) }
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runVirtualIfLeader")
fun <T> Client.legacyRunVirtualIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> = this.currentRunVirtualIfLeader(lockName, options, action)
