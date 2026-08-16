package io.bluetape4k.leader.diagnostics

import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.forTenant
import io.bluetape4k.leader.coroutines.withListeners
import io.bluetape4k.leader.forTenant
import io.bluetape4k.leader.local.LocalAsyncLeaderElector
import io.bluetape4k.leader.local.LocalAsyncLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.leader.local.LocalVirtualThreadLeaderElector
import io.bluetape4k.leader.local.LocalVirtualThreadLeaderGroupElector
import io.bluetape4k.leader.withListeners
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLeaderBackendDiagnosticsTest {

    @Test
    fun `Local descriptor는 모든 실행 모델과 single group 기능을 보고한다`() {
        val descriptor = LocalLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "local"
        descriptor.displayName shouldBeEqualTo "Local"
        capabilities.singleExecutionModels shouldBeEqualTo LeaderExecutionModel.entries.toSet()
        capabilities.groupExecutionModels shouldBeEqualTo LeaderExecutionModel.entries.toSet()
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo singleOnlyModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.PROCESS
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.CLIENT_LEASE
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `Local connectivity는 외부 I O 없이 UP을 반환한다`() {
        val connectivity = LocalLeaderBackendDiagnostics.checkConnectivity(100.milliseconds)

        connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.UP
        connectivity.checkedAt.shouldNotBeNull()
        connectivity.latencyMillis.shouldBeNull()
    }

    @Test
    fun `모든 canonical Local elector는 동일한 descriptor를 제공한다`() {
        val electors = listOf(
            LocalLeaderElector(),
            LocalAsyncLeaderElector(),
            LocalVirtualThreadLeaderElector(),
            LocalSuspendLeaderElector(),
            LocalLeaderGroupElector(),
            LocalAsyncLeaderGroupElector(),
            LocalVirtualThreadLeaderGroupElector(),
            LocalSuspendLeaderGroupElector(),
        )

        electors.forEach { elector ->
            val provider = elector as LeaderBackendDiagnosticsProvider
            provider.backendDescriptor shouldBe LocalLeaderBackendDiagnostics.backendDescriptor
        }
    }

    @Test
    fun `listening wrapper와 tenant wrapper는 Local provider를 전달한다`() {
        val wrappers = listOf(
            LocalLeaderElector().withListeners(),
            LocalLeaderElector().forTenant("tenant"),
            LocalLeaderElector().withListeners().forTenant("tenant").withListeners(),
            (LocalVirtualThreadLeaderElector() as VirtualThreadLeaderElector).forTenant("tenant"),
            LocalLeaderGroupElector().withListeners(),
            LocalLeaderGroupElector().forTenant("tenant"),
            LocalVirtualThreadLeaderGroupElector().forTenant("tenant"),
            (LocalSuspendLeaderElector() as SuspendLeaderElector).withListeners(),
            (LocalSuspendLeaderElector() as SuspendLeaderElector).forTenant("tenant"),
            (LocalSuspendLeaderGroupElector() as SuspendLeaderGroupElector).withListeners(),
            (LocalSuspendLeaderGroupElector() as SuspendLeaderGroupElector).forTenant("tenant"),
        )

        wrappers.forEach { wrapper ->
            val provider = (wrapper as? LeaderBackendDiagnosticsAware)?.backendDiagnosticsProvider

            provider.shouldNotBeNull().backendDescriptor shouldBe LocalLeaderBackendDiagnostics.backendDescriptor
        }
    }

    @Test
    fun `diagnostics가 없는 custom elector wrapper는 provider를 광고하지 않는다`() {
        val wrappers = listOf(
            mockk<LeaderElector>(relaxed = true).withListeners(),
            mockk<LeaderElector>(relaxed = true).forTenant("tenant"),
            mockk<LeaderElector>(relaxed = true).withListeners().forTenant("tenant").withListeners(),
            mockk<VirtualThreadLeaderElector>(relaxed = true).forTenant("tenant"),
            mockk<LeaderGroupElector>(relaxed = true).withListeners(),
            mockk<LeaderGroupElector>(relaxed = true).forTenant("tenant"),
            mockk<VirtualThreadLeaderGroupElector>(relaxed = true).forTenant("tenant"),
            mockk<SuspendLeaderElector>(relaxed = true).withListeners(),
            mockk<SuspendLeaderElector>(relaxed = true).forTenant("tenant"),
            mockk<SuspendLeaderGroupElector>(relaxed = true).withListeners(),
            mockk<SuspendLeaderGroupElector>(relaxed = true).forTenant("tenant"),
        )

        wrappers.forEach { wrapper ->
            wrapper
                .shouldBeInstanceOf<LeaderBackendDiagnosticsAware>()
                .backendDiagnosticsProvider
                .shouldBeNull()
        }
    }

    private companion object {
        val supportedModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.SUPPORTED,
        )
        val singleOnlyModes = LeaderBackendModeSupport(
            single = LeaderBackendSupport.SUPPORTED,
            group = LeaderBackendSupport.UNSUPPORTED,
        )
    }
}
