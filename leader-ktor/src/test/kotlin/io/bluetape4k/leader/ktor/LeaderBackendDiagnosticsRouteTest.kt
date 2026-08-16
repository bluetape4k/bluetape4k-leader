package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaderBackendDiagnosticsRouteTest {

    @Test
    fun `backend diagnostics route는 기본적으로 비활성화한다`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = RecordingDiagnosticsElector()
                }
            }
            startApplication()

            val response = client.get(LeaderElectionPluginConfig.DefaultBackendDiagnosticsRoutePath)

            response shouldHaveStatus HttpStatusCode.NotFound
        }
    }

    @Test
    fun `정적 diagnostics route는 probe 없이 descriptor를 반환한다`() = runSuspendIO {
        val elector = RecordingDiagnosticsElector()

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = elector
                    backendDiagnosticsRouteEnabled = true
                }
            }
            startApplication()

            val response = client.get(LeaderElectionPluginConfig.DefaultBackendDiagnosticsRoutePath)

            response shouldHaveStatus HttpStatusCode.OK
            response.bodyAsText() shouldBeEqualTo LocalDiagnosticsJson
            elector.probeCalls.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `connectivity check는 설정한 timeout으로 provider를 한 번 호출한다`() = runSuspendIO {
        val elector = RecordingDiagnosticsElector()

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = elector
                    backendDiagnosticsRouteEnabled = true
                    backendConnectivityCheckEnabled = true
                    backendConnectivityCheckTimeout = 275.milliseconds
                }
            }
            startApplication()

            val response = client.get(LeaderElectionPluginConfig.DefaultBackendDiagnosticsRoutePath)

            response shouldHaveStatus HttpStatusCode.OK
            response.bodyAsText() shouldBeEqualTo LocalConnectivityJson
            elector.probeCalls.get() shouldBeEqualTo 1
            elector.lastTimeout shouldBeEqualTo 275.milliseconds
        }
    }

    @Test
    fun `backend diagnostics route는 사용자 경로를 지원한다`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = RecordingDiagnosticsElector()
                    backendDiagnosticsRouteEnabled = true
                    backendDiagnosticsRoutePath = "internal/leader-backend"
                }
            }
            startApplication()

            val response = client.get("/internal/leader-backend")

            response shouldHaveStatus HttpStatusCode.OK
        }
    }

    @Test
    fun `diagnostics aware wrapper가 전달한 provider를 사용한다`() = runSuspendIO {
        val provider = RecordingDiagnosticsElector()

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = DiagnosticsAwareElector(provider)
                    backendDiagnosticsRouteEnabled = true
                }
            }
            startApplication()

            val response = client.get(LeaderElectionPluginConfig.DefaultBackendDiagnosticsRoutePath)

            response shouldHaveStatus HttpStatusCode.OK
            response.bodyAsText() shouldBeEqualTo LocalDiagnosticsJson
        }
    }

    @Test
    fun `provider가 없는 elector로 diagnostics route를 활성화하면 설치에 실패한다`() = runSuspendIO {
        val error = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) {
                        leaderElection = PlainSuspendLeaderElector()
                        backendDiagnosticsRouteEnabled = true
                    }
                }
                startApplication()
            }
        }

        error.message shouldBeEqualTo
                "backendDiagnosticsRouteEnabled=true 이면 leaderElection 이 backend diagnostics provider를 제공해야 합니다."
    }

    private class RecordingDiagnosticsElector :
        SuspendLeaderElector by LocalSuspendLeaderElector(),
        LeaderBackendDiagnosticsProvider {

        val probeCalls = AtomicInteger()
        var lastTimeout: Duration? = null

        override val backendDescriptor: LeaderBackendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor

        override fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
            probeCalls.incrementAndGet()
            lastTimeout = timeout
            return LeaderBackendConnectivity.up(CheckedAt, latencyMillis = 7L)
        }
    }

    private class DiagnosticsAwareElector(
        override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?,
    ) : SuspendLeaderElector by LocalSuspendLeaderElector(), LeaderBackendDiagnosticsAware

    private class PlainSuspendLeaderElector : SuspendLeaderElector by LocalSuspendLeaderElector()

    private companion object {
        val CheckedAt: Instant = Instant.parse("2026-08-16T00:00:00Z")

        const val LocalDiagnosticsJson: String =
            "{\"descriptor\":{\"backendId\":\"local\",\"displayName\":\"Local\",\"capabilities\":" +
                    "{\"singleExecutionModels\":[\"BLOCKING\",\"ASYNC\",\"SUSPEND\",\"VIRTUAL_THREAD\"]," +
                    "\"groupExecutionModels\":[\"BLOCKING\",\"ASYNC\",\"SUSPEND\",\"VIRTUAL_THREAD\"]," +
                    "\"leaseExtension\":{\"single\":\"SUPPORTED\",\"group\":\"SUPPORTED\"}," +
                    "\"auditState\":{\"single\":\"SUPPORTED\",\"group\":\"UNSUPPORTED\"}," +
                    "\"clockSource\":\"PROCESS\",\"ttlMode\":\"CLIENT_LEASE\",\"limitations\":[]}}," +
                    "\"connectivity\":{\"status\":\"NOT_CHECKED\",\"checkedAt\":null,\"latencyMillis\":null}}"

        const val LocalConnectivityJson: String =
            "{\"descriptor\":{\"backendId\":\"local\",\"displayName\":\"Local\",\"capabilities\":" +
                    "{\"singleExecutionModels\":[\"BLOCKING\",\"ASYNC\",\"SUSPEND\",\"VIRTUAL_THREAD\"]," +
                    "\"groupExecutionModels\":[\"BLOCKING\",\"ASYNC\",\"SUSPEND\",\"VIRTUAL_THREAD\"]," +
                    "\"leaseExtension\":{\"single\":\"SUPPORTED\",\"group\":\"SUPPORTED\"}," +
                    "\"auditState\":{\"single\":\"SUPPORTED\",\"group\":\"UNSUPPORTED\"}," +
                    "\"clockSource\":\"PROCESS\",\"ttlMode\":\"CLIENT_LEASE\",\"limitations\":[]}}," +
                    "\"connectivity\":{\"status\":\"UP\",\"checkedAt\":\"2026-08-16T00:00:00Z\"," +
                    "\"latencyMillis\":7}}"
    }
}
