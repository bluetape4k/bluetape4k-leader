package io.bluetape4k.leader.mongodb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.diagnostics.LeaderBackendClockSource
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityStatus
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivityReason
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendTtlMode
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MongoLeaderBackendDiagnosticsTest {

    @Test
    fun `MongoDB descriptor는 native single group 실행 모델과 DB lease 계약을 보고한다`() {
        val descriptor = MongoLeaderBackendDiagnostics.backendDescriptor
        val capabilities = descriptor.capabilities

        descriptor.backendId shouldBeEqualTo "mongodb"
        descriptor.displayName shouldBeEqualTo "MongoDB"
        capabilities.singleExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.groupExecutionModels shouldBeEqualTo nativeExecutionModels
        capabilities.leaseExtension shouldBeEqualTo supportedModes
        capabilities.auditState shouldBeEqualTo unsupportedModes
        capabilities.clockSource shouldBeEqualTo LeaderBackendClockSource.PROCESS
        capabilities.ttlMode shouldBeEqualTo LeaderBackendTtlMode.CLIENT_LEASE
        capabilities.limitations shouldBeEqualTo emptyList()
    }

    @Test
    fun `MongoDB connectivity는 안전한 bounded probe가 없어 UNKNOWN을 반환한다`() {
        val connectivity = MongoLeaderBackendDiagnostics
            .checkConnectivity(100.milliseconds)

        connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.UNKNOWN
        connectivity.reason shouldBeEqualTo LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED
    }

    @Test
    fun `MongoDB probe는 공통 helper의 positive finite timeout 계약을 적용한다`() {
        val zero = assertFailsWith<IllegalArgumentException> {
            MongoLeaderBackendDiagnostics.checkConnectivity(Duration.ZERO)
        }
        zero.message shouldContain "probe timeout[0s] must be greater than 0s."

        val negative = assertFailsWith<IllegalArgumentException> {
            MongoLeaderBackendDiagnostics.checkConnectivity((-1).milliseconds)
        }
        negative.message shouldContain "probe timeout[-1ms] must be greater than 0s."

        val infinite = assertFailsWith<IllegalArgumentException> {
            MongoLeaderBackendDiagnostics.checkConnectivity(Duration.INFINITE)
        }
        infinite.message shouldContain "probe timeout must be finite"
    }

    @Test
    fun `모든 canonical MongoDB elector는 diagnostics provider를 구현한다`() {
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(MongoLeaderElector::class.java) shouldBe true
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(MongoLeaderGroupElector::class.java) shouldBe true
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(MongoSuspendLeaderElector::class.java) shouldBe true
        LeaderBackendDiagnosticsProvider::class.java
            .isAssignableFrom(MongoSuspendLeaderGroupElector::class.java) shouldBe true
    }

    private companion object {
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
}
