package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionManagementRouteTest {

    @Test
    fun `management route is disabled by default`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                }
            }
            startApplication()

            val response = client.get("/management/leaderElection")

            response.status shouldBeEqualTo HttpStatusCode.NotFound
        }
    }

    @Test
    fun `management route returns configured lock status`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                    managementRouteEnabled = true
                    managementLockNames("batch-job")
                }
            }
            startApplication()

            val response = client.get("/management/leaderElection")

            response.status shouldBeEqualTo HttpStatusCode.OK
            response.bodyAsText() shouldBeEqualTo
                """{"locks":[{"name":"batch-job","status":"Empty","leaderId":null,"leaseExpiry":null}]}"""
        }
    }

    @Test
    fun `management route supports custom path`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                    managementRouteEnabled = true
                    managementRoutePath = "/internal/leader-status"
                    managementLockNames("batch-job")
                }
            }
            startApplication()

            val response = client.get("/internal/leader-status")

            response.status shouldBeEqualTo HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"name\":\"batch-job\""
        }
    }

    @Test
    fun `leaderScheduled records lock name for management route`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                    managementRouteEnabled = true
                }

                leaderScheduled("scheduled-job", 1.seconds) {
                    // no-op
                }
            }
            startApplication()

            val response = client.get("/management/leaderElection")

            response.status shouldBeEqualTo HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"name\":\"scheduled-job\""
        }
    }
}
