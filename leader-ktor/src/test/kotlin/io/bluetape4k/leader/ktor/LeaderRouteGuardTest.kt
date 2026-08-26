package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

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

    private class FailingStateElector(
        private val failure: Throwable,
    ) : SuspendLeaderElector {
        override val supportsAuditLeaderState: Boolean = true

        override fun state(lockName: String): LeaderState = throw failure

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }
}
