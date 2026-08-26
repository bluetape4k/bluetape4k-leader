package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderManagementActionObservation
import io.bluetape4k.leader.LeaderManagementActionObserver
import io.bluetape4k.leader.LeaderManagementActionSurface
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.principal
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionManagementActionRouteTest {

    private val registries = mutableListOf<RecordingRegistry>()

    @AfterEach
    fun closeRegistries() {
        registries.forEach { it.delegate.close() }
        registries.clear()
    }

    @Test
    fun `POST is absent until application explicitly installs action route`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                    managementActionRouteEnabled = true
                    managementActionRegistry = registry.delegate
                }
            }
            startApplication()

            client.post("/management/leaderElection/actions/batch-job") shouldHaveStatus HttpStatusCode.NotFound
        }
    }

    @Test
    fun `custom management action path is explicit and isolated from canonical path`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(
                    registry = registry,
                    managementRoutePath = "/internal/leader-status",
                    actionPath = "/internal/leader-status/actions",
                    authorize = { true },
                )
            }
            startApplication()

            client.post("/internal/leader-status/actions/batch-job") {
                managementCredentials()
            } shouldHaveStatus HttpStatusCode.OK
            client.post("/management/leaderElection/actions/batch-job") {
                managementCredentials()
            } shouldHaveStatus HttpStatusCode.NotFound
        }
    }

    @Test
    fun `disabled action route fails closed even when extension is installed`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LocalSuspendLeaderElector()
                    managementActionRegistry = registry.delegate
                }
                routing {
                    leaderElectionManagementActionRoute(
                        registry = registry.delegate,
                        authorize = { true },
                    )
                }
            }
            startApplication()

            client.post("/management/leaderElection/actions/batch-job") shouldHaveStatus HttpStatusCode.NotFound
        }
    }

    @Test
    fun `enabled action route requires an application-owned registry`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) {
                        leaderElection = LocalSuspendLeaderElector()
                        managementActionRouteEnabled = true
                    }
                }
                startApplication()
            }
        }
    }

    @Test
    fun `authentication provider owns unauthenticated response`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(registry) { true }
            }
            startApplication()

            val response = client.post("/management/leaderElection/actions/batch-job")

            response shouldHaveStatus HttpStatusCode.Unauthorized
        }
    }

    @Test
    fun `authorize false returns fixed forbidden error without backend details`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(registry) { false }
            }
            startApplication()

            val response = client.post("/management/leaderElection/actions/batch-job") {
                managementCredentials()
            }

            response shouldHaveStatus HttpStatusCode.Forbidden
            response.bodyAsText() shouldBeEqualTo
                "{\"code\":\"AUTHORIZATION_DENIED\",\"message\":\"management action authorization denied\"}"
        }
    }

    @Test
    fun `authorize exception is sanitized and does not invoke registry`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(registry) { error("credential-secret") }
            }
            startApplication()

            val response = client.post("/management/leaderElection/actions/batch-job") {
                managementCredentials()
            }

            response shouldHaveStatus HttpStatusCode.InternalServerError
            response.bodyAsText() shouldBeEqualTo
                "{\"code\":\"AUTHORIZATION_FAILED\",\"message\":\"management action authorization failed\"}"
            response.bodyAsText().contains("credential-secret").shouldBeEqualTo(false)
            registry.releaseCalls.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `authorized release returns shared mapping and allow-listed JSON`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(registry) { principal<UserIdPrincipal>()?.name == "admin" }
            }
            startApplication()

            val response = client.post("/management/leaderElection/actions/batch-job") {
                managementCredentials()
            }

            response shouldHaveStatus HttpStatusCode.OK
            response.bodyAsText() shouldBeEqualTo
                "{\"action\":\"RELEASE\",\"outcome\":\"RELEASED\",\"mutationAttempted\":true}"
            response.headers[HttpHeaders.ContentType].orEmpty() shouldContain "application/json"
            registry.observations.single().surface shouldBeEqualTo LeaderManagementActionSurface.KTOR
        }
    }

    @Test
    fun `hostile selector is rejected by shared lock grammar`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(registry) { true }
            }
            startApplication()

            val response = client.post("/management/leaderElection/actions/bad%25job") {
                managementCredentials()
            }

            response shouldHaveStatus HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain "\"outcome\":\"INVALID_LOCK_NAME\""
            registry.releaseCalls.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `encoded slash does not cross the lock selector boundary`() = runSuspendIO {
        val registry = registryWithHandle()
        testApplication {
            application {
                installActionRoute(registry) { true }
            }
            startApplication()

            val response = client.post("/management/leaderElection/actions/a%2Fb") {
                managementCredentials()
            }

            response shouldHaveStatus HttpStatusCode.NotFound
            registry.releaseCalls.get() shouldBeEqualTo 0
        }
    }

    private fun Application.installActionRoute(
        registry: RecordingRegistry,
        managementRoutePath: String = LeaderElectionPluginConfig.DefaultManagementRoutePath,
        actionPath: String? = null,
        authorize: suspend io.ktor.server.application.ApplicationCall.() -> Boolean,
    ) {
        install(io.ktor.server.auth.Authentication) {
            basic("management") {
                validate { credentials ->
                    if (credentials.name == "admin" && credentials.password == "secret") {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                }
            }
        }
        install(LeaderElectionPlugin) {
            leaderElection = LocalSuspendLeaderElector()
            this.managementRoutePath = managementRoutePath
            managementActionRouteEnabled = true
            managementActionRegistry = registry.delegate
            managementActionRoutePath = actionPath
        }
        routing {
            authenticate("management") {
                leaderElectionManagementActionRoute(
                    path = actionPath ?: "$managementRoutePath/actions",
                    registry = registry.delegate,
                    authorize = authorize,
                )
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.managementCredentials() {
        val encoded = Base64.getEncoder().encodeToString("admin:secret".toByteArray())
        header(HttpHeaders.Authorization, "Basic $encoded")
    }

    private fun registryWithHandle(): RecordingRegistry {
        val observations = mutableListOf<LeaderManagementActionObservation>()
        val registry = SuspendLeaderManagementActionRegistry(
            observer = LeaderManagementActionObserver { observations += it },
        )
        val handle = TestHandle("batch-job")
        registry.register(handle)
        return RecordingRegistry(registry, handle, observations).also(registries::add)
    }

    private class RecordingRegistry(
        val delegate: SuspendLeaderManagementActionRegistry,
        private val handle: TestHandle,
        val observations: List<LeaderManagementActionObservation>,
    ) {
        val releaseCalls: AtomicInteger
            get() = handle.releaseCalls
    }

    private class TestHandle(
        override val lockName: String,
    ) : SuspendLeaderLeaseHandle {
        private var ownershipCalls = 0

        override val auditLeaderId: String = "test-leader"
        override val acquiredAt: Instant = Instant.EPOCH

        override suspend fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome = ExtendOutcome.NotHeld

        override suspend fun ownershipStatus(): LeaseOwnershipStatus =
            if (ownershipCalls++ == 0) LeaseOwnershipStatus.HELD else LeaseOwnershipStatus.NOT_HELD

        override suspend fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        val releaseCalls = AtomicInteger()

        override suspend fun release() {
            releaseCalls.incrementAndGet()
        }
    }
}
