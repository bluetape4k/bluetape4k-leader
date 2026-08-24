package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import org.apache.curator.framework.CuratorFramework
import java.time.Clock
import kotlin.time.Duration

/** 기존 Curator client의 연결 상태만 읽는 ZooKeeper diagnostics provider입니다. */
class ZooKeeperLeaderBackendDiagnostics(
    private val client: CuratorFramework,
) : LeaderBackendDiagnosticsProvider {

    override val backendDescriptor: LeaderBackendDescriptor = Descriptor

    /** 네트워크 요청 없이 기존 Curator client의 연결 상태를 확인합니다. */
    override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        require(timeout.isFinite() && timeout > Duration.ZERO) {
            "probe timeout must be positive and finite: $timeout"
        }
        val checkedAt = Clock.systemUTC().instant()
        return try {
            if (client.zookeeperClient.isConnected) {
                LeaderBackendConnectivity.up(checkedAt)
            } else {
                LeaderBackendConnectivity.down(checkedAt)
            }
        } catch (_: Exception) {
            LeaderBackendConnectivity.unknown(checkedAt)
        }
    }

    private companion object {
        val SupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val UnsupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.UNSUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
        val NativeExecutionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )

        val Descriptor = LeaderBackendDescriptor(
            backendId = "zookeeper",
            displayName = "ZooKeeper",
            capabilities = LeaderBackendCapabilities(
                singleExecutionModels = NativeExecutionModels,
                groupExecutionModels = NativeExecutionModels,
                leaseExtension = SupportedModes,
                auditState = UnsupportedModes,
                clockSource = LeaderBackendClockSource.NOT_APPLICABLE,
                ttlMode = LeaderBackendTtlMode.SESSION,
            ),
        )
    }
}
