package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.coroutines.cancellation.CancellationException
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

            response shouldHaveStatus HttpStatusCode.NotFound
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

            response shouldHaveStatus HttpStatusCode.OK
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

            response shouldHaveStatus HttpStatusCode.OK
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

            response shouldHaveStatus HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"name\":\"scheduled-job\""
        }
    }

    @Test
    fun `invalid lock name is mapped to a stable bad request`() = runSuspendIO {
        val registry = LeaderElectionManagementRegistry().also { it.addInvalidLockForTest("bad name") }

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                }
                leaderElectionManagementRoute(registry = registry)
            }
            startApplication()

            val response = client.get("/management/leaderElection")

            response shouldHaveStatus HttpStatusCode.BadRequest
            response.bodyAsText() shouldBeEqualTo
                "{\"code\":\"INVALID_LOCK_NAME\",\"message\":\"lock name is invalid\",\"status\":400}"
        }
    }

    @Test
    fun `state provider failure is mapped to unavailable without backend detail`() = runSuspendIO {
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = FailingStateElector(IllegalArgumentException("backend-secret"))
                }
                leaderElectionManagementRoute(registry = LeaderElectionManagementRegistry(listOf("batch-job")))
            }
            startApplication()

            val response = client.get("/management/leaderElection")

            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "\"code\":\"BACKEND_UNAVAILABLE\""
            response.bodyAsText() shouldContain "\"status\":503"
            response.bodyAsText().contains("backend-secret") shouldBeEqualTo false
        }
    }

    @Test
    fun `request cancellation is rethrown from management route`() = runSuspendIO {
        val registry = LeaderElectionManagementRegistry(listOf("batch-job"))

        assertFailsWith<CancellationException> {
            registry.toJson(FailingStateElector(CancellationException("cancelled")))
        }
    }

    private class FailingStateElector(
        private val failure: Throwable,
    ) : SuspendLeaderElector {
        override fun state(lockName: String): LeaderState = throw failure

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }

    private fun LeaderElectionManagementRegistry.addInvalidLockForTest(lockName: String) {
        val field = LeaderElectionManagementRegistry::class.java.getDeclaredField("lockNames")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val names = field.get(this) as MutableSet<String>
        names.add(lockName)
    }
}
