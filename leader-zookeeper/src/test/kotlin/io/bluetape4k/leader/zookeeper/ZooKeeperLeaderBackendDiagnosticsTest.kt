package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.apache.curator.CuratorZookeeperClient
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ZooKeeperLeaderBackendDiagnosticsTest {

    private companion object: KLogging() {
        val nativeExecutionModels = setOf(
            LeaderExecutionModel.BLOCKING,
            LeaderExecutionModel.ASYNC,
            LeaderExecutionModel.SUSPEND,
        )
        val supportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val unsupportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.UNSUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }

    private val zookeeperClient = mockk<CuratorZookeeperClient>(relaxed = true)
    private val client = mockk<CuratorFramework>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(zookeeperClient, client)
    }

    @Test
    fun `descriptor는 ZooKeeper 실행 모델과 session 계약을 보고한다`() {
        val descriptor = ZooKeeperLeaderBackendDiagnostics(mockk(relaxed = true)).backendDescriptor
        log.debug { "descriptor=$descriptor" }
        descriptor.backendId shouldBeEqualTo "zookeeper"
        descriptor.displayName shouldBeEqualTo "ZooKeeper"

        val capabilities = descriptor.capabilities
        log.debug { "capabilities=$capabilities" }
        capabilities.singleExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.groupExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.NOT_APPLICABLE
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.SESSION
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `connectivity는 기존 Curator connection 상태만 읽는다`() {
        every { client.zookeeperClient } returns zookeeperClient
        every { zookeeperClient.isConnected } returnsMany listOf(true, false)
        val provider = ZooKeeperLeaderBackendDiagnostics(client)

        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.UP
        provider.checkConnectivity(100.milliseconds).status shouldBeEqualTo LeaderBackendConnectivityStatus.DOWN
    }

    @Test
    fun `Curator Exception은 UNKNOWN으로 정규화한다`() {
        every { client.zookeeperClient } returns zookeeperClient
        every { zookeeperClient.isConnected } throws IllegalStateException("probe failed")

        ZooKeeperLeaderBackendDiagnostics(client)
            .checkConnectivity(100.milliseconds)
            .status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
    }

    @Test
    fun `Curator Error는 동일 인스턴스로 재전파한다`() {
        val fatal = AssertionError("fatal ZooKeeper probe")
        every { client.zookeeperClient } returns zookeeperClient
        every { zookeeperClient.isConnected } throws fatal

        val thrown = assertFailsWith<AssertionError> {
            ZooKeeperLeaderBackendDiagnostics(client).checkConnectivity(100.milliseconds)
        }

        thrown shouldBeSameInstanceAs fatal
    }

    @Test
    fun `모든 canonical ZooKeeper elector는 동일한 diagnostics provider를 구현한다`() {
        listOf(
            ZooKeeperLeaderElector::class.java,
            ZooKeeperLeaderGroupElector::class.java,
            ZooKeeperSuspendLeaderElector::class.java,
            ZooKeeperSuspendLeaderGroupElector::class.java,
        ).forEach { electorType ->
            LeaderBackendDiagnosticsProvider::class.java.isAssignableFrom(electorType).shouldBeTrue()
        }
    }
}
