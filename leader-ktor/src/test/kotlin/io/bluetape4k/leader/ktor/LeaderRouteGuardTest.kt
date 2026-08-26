package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LeaderRouteGuardTest {

    @Test
    fun `Occupied STATE는 downstream을 실행한다`() = runSuspendIO {
        val downstream = AtomicInteger(0)

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = FakeSuspendLeaderElector(
                        stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
                    )
                }
                routing {
                    leaderGuard("job") {
                        get {
                            downstream.incrementAndGet()
                            call.respondText("ok")
                        }
                    }
                }
            }
            startApplication()

            client.get("/").bodyAsText() shouldBeEqualTo "ok"
        }

        downstream.get() shouldBeEqualTo 1
    }

    @Test
    fun `Empty STATE는 downstream을 호출하지 않고 NOT_LEADER 503을 반환한다`() = runSuspendIO {
        val downstream = AtomicInteger(0)

        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = FakeSuspendLeaderElector() }
                routing {
                    leaderGuard("job") {
                        get {
                            downstream.incrementAndGet()
                            call.respondText("ok")
                        }
                    }
                }
            }
            startApplication()

            val response = client.get("/")
            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "\"code\":\"NOT_LEADER\""
        }

        downstream.get() shouldBeEqualTo 0
    }

    @Test
    fun `STATE는 상태 provider를 정확히 한 번 읽는다`() = runSuspendIO {
        val reads = AtomicInteger(0)
        val elector = FakeSuspendLeaderElector(supportsAuditLeaderState = false)

        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }
                routing {
                    leaderGuard(
                        lockName = "job",
                        configure = { stateProvider = { reads.incrementAndGet(); LeaderState.occupied("job", LeaderLease("test-node")) } },
                    ) {
                        get { call.respondText("ok") }
                    }
                }
            }
            startApplication()

            client.get("/").bodyAsText() shouldBeEqualTo "ok"
        }

        reads.get() shouldBeEqualTo 1
    }

    @Test
    fun `STATE provider failure는 backend detail 없이 503을 반환한다`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = FailingStateElector(IllegalStateException("backend-secret"))
                }
                routing {
                    leaderGuard("job") {
                        get { call.respondText("unreachable") }
                    }
                }
            }
            startApplication()

            val response = client.get("/")
            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "\"code\":\"BACKEND_UNAVAILABLE\""
            response.bodyAsText().contains("backend-secret") shouldBeEqualTo false
        }
    }

    @Test
    fun `STATE에서 audit state를 지원하지 않는 기본 elector는 startup configuration error다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) {
                        leaderElection = FakeSuspendLeaderElector(supportsAuditLeaderState = false)
                    }
                    routing {
                        leaderGuard("job") {
                            get { call.respondText("unreachable") }
                        }
                    }
                }
                startApplication()
            }
        }
    }

    @Test
    fun `explicit state provider는 audit state capability 없이도 startup된다`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = FakeSuspendLeaderElector(supportsAuditLeaderState = false)
                }
                routing {
                    leaderGuard("job", configure = {
                        stateProvider = { LeaderState.occupied("job", LeaderLease("test-node")) }
                    }) {
                        get { call.respondText("ok") }
                    }
                }
            }
            startApplication()
        }
    }

    @Test
    fun `기본 STATE mode는 lease acquire를 호출하지 않는다`() = runSuspendIO {
        val handle = TrackingLeaseHandle()
        val acquirer = CountingLeaseAcquirer(handle)

        val response = runGuardRequest(
            elector = FakeSuspendLeaderElector(
                stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
            ),
            guard = { leaseAcquirer = acquirer },
        )

        response shouldHaveStatus HttpStatusCode.OK
        acquirer.acquireCount.get() shouldBeEqualTo 0
        handle.releaseCount shouldBeEqualTo 0
    }

    @Test
    fun `LEASE contention은 LEADER_LOCKED 423이고 release하지 않는다`() = runSuspendIO {
        val acquirer = CountingLeaseAcquirer(handle = null)

        val response = runGuardRequest(
            elector = FakeSuspendLeaderElector(
                stateValue = LeaderState.occupied("job", LeaderLease("test-node")),
            ),
            guard = {
                authorityMode = LeaderRouteAuthorityMode.LEASE
                leaseAcquirer = acquirer
            },
        )

        response shouldHaveStatus HttpStatusCode.Locked
        response.bodyAsText() shouldContain "\"code\":\"LEADER_LOCKED\""
        acquirer.acquireCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `LEASE acquired request는 downstream 실행 후 release를 한 번 수행한다`() = runSuspendIO {
        val handle = TrackingLeaseHandle()
        val acquirer = CountingLeaseAcquirer(handle)

        val response = runGuardRequest(
            elector = FakeSuspendLeaderElector(),
            guard = {
                authorityMode = LeaderRouteAuthorityMode.LEASE
                leaseAcquirer = acquirer
            },
        )

        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText() shouldBeEqualTo "ok"
        acquirer.acquireCount.get() shouldBeEqualTo 1
        handle.releaseCount shouldBeEqualTo 1
    }

    @Test
    fun `LEASE downstream cancellation은 원래 cancellation을 유지하고 release는 한 번이다`() = runSuspendIO {
        val handle = TrackingLeaseHandle()
        val failure = assertFailsWith<CancellationException> {
            withLeaderRouteLease(handle, 50.milliseconds) {
                throw CancellationException("downstream cancelled")
            }
        }

        failure.message shouldBeEqualTo "downstream cancelled"
        handle.releaseCount shouldBeEqualTo 1
    }

    @Test
    fun `release가 hang해도 bounded cleanup 뒤 원래 cancellation을 유지한다`() = runSuspendIO {
        val releaseStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handle = TrackingLeaseHandle(
            releaseAction = {
                releaseStarted.complete(Unit)
                awaitCancellation()
            },
        )

        val failure = assertFailsWith<CancellationException> {
            withLeaderRouteLease(handle, 50.milliseconds) {
                throw CancellationException("downstream cancelled")
            }
        }

        releaseStarted.await()
        failure.message shouldBeEqualTo "downstream cancelled"
        handle.releaseCount shouldBeEqualTo 1
    }

    @Test
    fun `release failure는 성공 응답을 대체하지 않는다`() = runSuspendIO {
        val handle = TrackingLeaseHandle(releaseAction = { error("release failed") })

        val response = runGuardRequest(
            elector = FakeSuspendLeaderElector(),
            guard = {
                authorityMode = LeaderRouteAuthorityMode.LEASE
                leaseAcquirer = CountingLeaseAcquirer(handle)
                leaseMaxDuration = 50.milliseconds
            },
        )

        response shouldHaveStatus HttpStatusCode.OK
        response.bodyAsText() shouldBeEqualTo "ok"
        handle.releaseCount shouldBeEqualTo 1
    }

    @Test
    fun `LEASE capability가 없는 elector는 startup configuration error다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) {
                        leaderElection = UnavailableLeaseElector()
                    }
                    routing {
                        leaderGuard("job", configure = {
                            authorityMode = LeaderRouteAuthorityMode.LEASE
                        }) {
                            get { call.respondText("unreachable") }
                        }
                    }
                }
                startApplication()
            }
        }
    }

    @Test
    fun `LEASE acquirer가 없으면 startup configuration error다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) { leaderElection = FakeSuspendLeaderElector() }
                    routing {
                        leaderGuard("job", configure = {
                            authorityMode = LeaderRouteAuthorityMode.LEASE
                        }) {
                            get { call.respondText("unreachable") }
                        }
                    }
                }
                startApplication()
            }
        }
    }

    @Test
    fun `LEASE leaseMaxDuration은 유한한 양수여야 한다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) { leaderElection = FakeSuspendLeaderElector() }
                    routing {
                        leaderGuard("job", configure = {
                            authorityMode = LeaderRouteAuthorityMode.LEASE
                            leaseAcquirer = CountingLeaseAcquirer(handle = null)
                            leaseMaxDuration = Duration.ZERO
                        }) {
                            get { call.respondText("unreachable") }
                        }
                    }
                }
                startApplication()
            }
        }
    }

    private suspend fun runGuardRequest(
        elector: SuspendLeaderElector,
        guard: LeaderRouteGuardConfig.() -> Unit,
        downstream: suspend ApplicationCall.() -> Unit = { respondText("ok") },
    ): HttpResponse {
        lateinit var result: HttpResponse
        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }
                routing {
                    leaderGuard("job", configure = guard) {
                        get { call.downstream() }
                    }
                }
            }
            startApplication()
            result = client.get("/")
        }
        return result
    }

    private class UnavailableLeaseElector :
        SuspendLeaderElector,
        SuspendLeaderLeaseAcquirerSupport {
        override val supportsAuditLeaderState: Boolean = true
        override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer =
            CountingLeaseAcquirer(handle = null)
        override val leaseCapabilityAvailable: Boolean = false

        override fun state(lockName: String): LeaderState = LeaderState.empty(lockName)

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }

    private class FailingStateElector(
        private val failure: Throwable,
    ) : SuspendLeaderElector {
        override val supportsAuditLeaderState: Boolean = true

        override fun state(lockName: String): LeaderState = throw failure

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }
}
