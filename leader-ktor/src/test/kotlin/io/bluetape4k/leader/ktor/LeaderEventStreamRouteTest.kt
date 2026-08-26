package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub
import io.bluetape4k.leader.ktor.stream.LeaderStreamItem
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.utils.io.readLine
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderEventStreamRouteTest {

    @Test
    fun `enabled stream requires publisher and caller registrar`() {
        assertFailsWith<LeaderElectionConfigurationException> {
            testApplication {
                application {
                    install(SSE)
                    install(LeaderElectionPlugin) {
                        leaderElection = NonPublishingElector()
                        eventStreamRouteEnabled = true
                    }
                }
                startApplication()
            }
        }

        assertFailsWith<LeaderElectionConfigurationException> {
            testApplication {
                application {
                    install(SSE)
                    install(LeaderElectionPlugin) {
                        leaderElection = PublishingElector()
                        eventStreamRouteEnabled = true
                    }
                }
                startApplication()
            }
        }

        assertFailsWith<LeaderElectionConfigurationException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) {
                        leaderElection = PublishingElector()
                        eventStreamRouteEnabled = true
                    }
                    routing { leaderElectionEventStream() }
                }
                startApplication()
            }
        }

        assertFailsWith<LeaderElectionConfigurationException> {
            testApplication {
                application {
                    install(SSE)
                    install(LeaderElectionPlugin) {
                        leaderElection = PublishingElector()
                        eventStreamRouteEnabled = true
                    }
                    routing {
                        leaderElectionEventStream()
                        leaderElectionEventStream()
                    }
                }
                startApplication()
            }
        }
    }

    @Test
    fun `SSE는 authenticated caller route에서 event frame을 전달한다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(Authentication) {
                    basic("test") {
                        validate { UserIdPrincipal(it.name) }
                    }
                }
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                    eventStreamExposeLockName = true
                }
                routing {
                    authenticate("test") {
                        leaderElectionEventStream()
                    }
                }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            coroutineScope {
                val response = async {
                    client.prepareGet("/management/leaderElection/events?lockName=job") {
                        header(HttpHeaders.Authorization, basicAuth("user", "password"))
                    }.execute { received ->
                        received.status shouldBeEqualTo HttpStatusCode.OK
                        readSseFrame(received.bodyAsChannel())
                    }
                }
                hub.awaitSubscriberCount(1)
                publisher.emit(LeaderElectionEvent.Elected("job", leaderId = "node-a"))
                val frame = withTimeout(5.seconds) { response.await() }
                frame shouldContain "id: 1"
                frame shouldContain "event: Elected"
                frame shouldContain "data: {\"type\":\"Elected\",\"sequence\":1,\"lockName\":\"job\"}"
            }
        }
    }

    @Test
    fun `SSE heartbeat와 peer disconnect는 connection lifecycle을 정리한다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                    eventStreamReplayCapacity = 0
                    eventStreamHeartbeat = 25.milliseconds
                }
                routing { leaderElectionEventStream() }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            val responseJob = async {
                client.prepareGet("/management/leaderElection/events?lockName=job").execute { received ->
                    received.status shouldBeEqualTo HttpStatusCode.OK
                    readSseFrame(received.bodyAsChannel())
                }
            }
            hub.awaitSubscriberCount(1)
            val frame = withTimeout(5.seconds) { responseJob.await() }
            frame shouldContain "event: heartbeat"
            withTimeout(5.seconds) {
                while (hub.subscriberCount() != 0) yield()
            }
        }
    }

    @Test
    fun `SSE는 Last-Event-ID 이후 replay와 stale cursor gap을 전달한다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                    eventStreamReplayCapacity = 2
                }
                routing { leaderElectionEventStream() }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()
            publisher.emit(LeaderElectionEvent.Skipped("job"))
            publisher.emit(LeaderElectionEvent.Revoked("job"))
            awaitReplaySequence(hub, 2L)

            val response = client.prepareGet("/management/leaderElection/events?lockName=job") {
                header("Last-Event-ID", "1")
            }.execute { received ->
                received.status shouldBeEqualTo HttpStatusCode.OK
                readSseFrame(received.bodyAsChannel())
            }
            response shouldContain "id: 2"
            response shouldContain "event: Revoked"

            publisher.emit(LeaderElectionEvent.Skipped("job"))
            publisher.emit(LeaderElectionEvent.Elected("job", leaderId = "node-b"))
            withTimeout(5.seconds) {
                while (hub.replay(afterSequence = null).none {
                    it is LeaderStreamItem.Event && it.sequence == 4L
                }) {
                    yield()
                }
            }
            val staleResponse = client.prepareGet("/management/leaderElection/events?lockName=job") {
                header("Last-Event-ID", "0")
            }.execute { received ->
                received.status shouldBeEqualTo HttpStatusCode.OK
                readSseFrame(received.bodyAsChannel())
            }
            staleResponse shouldContain "event: replay_gap"
            staleResponse shouldContain "data: {\"event\":\"replay_gap\",\"from\":1,\"to\":2}"
        }
    }

    @Test
    fun `all-lock stream은 lockName 없이도 명시적으로 연결된다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                    eventStreamAllLocksEnabled = true
                    eventStreamExposeLockName = true
                }
                routing { leaderElectionEventStream() }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            coroutineScope {
                val response = async {
                    client.prepareGet("/management/leaderElection/events").execute { received ->
                        received.status shouldBeEqualTo HttpStatusCode.OK
                        readSseFrame(received.bodyAsChannel())
                    }
                }
                hub.awaitSubscriberCount(1)
                publisher.emit(LeaderElectionEvent.Skipped("all-lock-job"))
                val frame = withTimeout(5.seconds) { response.await() }
                frame shouldContain "\"lockName\":\"all-lock-job\""
            }
        }
    }

    @Test
    fun `잘못된 lockName과 cursor는 stream admission 전에 stable 400으로 거부한다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                }
                routing { leaderElectionEventStream() }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            val missingLock = client.get("/management/leaderElection/events")
            missingLock.status shouldBeEqualTo HttpStatusCode.BadRequest
            missingLock.bodyAsText() shouldContain "\"code\":\"INVALID_LOCK_NAME\""

            val invalidCursor = client.get("/management/leaderElection/events?lockName=job&afterSequence=-1")
            invalidCursor.status shouldBeEqualTo HttpStatusCode.BadRequest
            invalidCursor.bodyAsText() shouldContain "\"code\":\"INVALID_CURSOR\""

            val duplicateCursor = client.prepareGet(
                "/management/leaderElection/events?lockName=job&afterSequence=1",
            ) {
                header("Last-Event-ID", "1")
            }.execute { received ->
                received.status shouldBeEqualTo HttpStatusCode.BadRequest
                received.bodyAsText()
            }
            duplicateCursor shouldContain "\"code\":\"INVALID_CURSOR\""

            hub.subscriberCount() shouldBeEqualTo 0
        }
    }

    @Test
    fun `SSE admission 상한과 disconnect 이후 permit 반환을 보장한다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                    eventStreamMaxConnections = 1
                    eventStreamHeartbeat = 5.seconds
                }
                routing { leaderElectionEventStream() }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            val first = async {
                client.prepareGet("/management/leaderElection/events?lockName=job").execute { received ->
                    received.status shouldBeEqualTo HttpStatusCode.OK
                    received.bodyAsChannel().readLine()
                }
            }
            hub.awaitSubscriberCount(1)

            val rejected = client.get("/management/leaderElection/events?lockName=job")
            rejected.status shouldBeEqualTo HttpStatusCode.ServiceUnavailable
            rejected.bodyAsText() shouldContain "\"code\":\"BACKEND_UNAVAILABLE\""

            first.cancelAndJoin()
            publisher.emit(LeaderElectionEvent.Skipped("job"))
            withTimeout(5.seconds) {
                while (hub.subscriberCount() != 0) yield()
            }

            val second = async {
                client.prepareGet("/management/leaderElection/events?lockName=job").execute { received ->
                    received.status shouldBeEqualTo HttpStatusCode.OK
                    readSseFrame(received.bodyAsChannel())
                }
            }
            hub.awaitSubscriberCount(1)
            publisher.emit(LeaderElectionEvent.Skipped("job"))
            second.await() shouldContain "event: Skipped"
        }
    }

    @Test
    fun `authentication rejection은 hub subscriber를 만들지 않는다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(SSE)
                install(Authentication) {
                    basic("test") {
                        validate { if (it.name == "ok") UserIdPrincipal(it.name) else null }
                    }
                }
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                }
                routing {
                    authenticate("test") { leaderElectionEventStream() }
                }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            val response = client.get("/management/leaderElection/events?lockName=job")
            response.status shouldBeEqualTo HttpStatusCode.Unauthorized
            hub.subscriberCount() shouldBeEqualTo 0
        }
    }

    @Test
    fun `WebSocket는 handshake, filter, cursor replay gap, frame 전송과 disconnect cleanup을 지원한다`() = runSuspendIO {
        val publisher = PublishingElector()
        lateinit var hub: LeaderEventStreamHub

        testApplication {
            application {
                install(WebSockets)
                install(LeaderElectionPlugin) {
                    leaderElection = publisher
                    eventStreamRouteEnabled = true
                    eventStreamSseEnabled = false
                    eventStreamWebSocketEnabled = true
                    eventStreamExposeLockName = true
                    eventStreamExposeLeaderMetadata = true
                    eventStreamReplayCapacity = 2
                    eventStreamHeartbeat = 5.seconds
                }
                routing { leaderElectionEventStream() }
                hub = plugin(LeaderEventStreamRuntimePlugin).hub
            }
            startApplication()

            publisher.emit(LeaderElectionEvent.Skipped("old-1"))
            publisher.emit(LeaderElectionEvent.Skipped("old-2"))
            publisher.emit(LeaderElectionEvent.Skipped("old-3"))
            awaitReplaySequence(hub, 3L)

            val wsClient = createClient {
                install(ClientWebSockets)
            }
            wsClient.webSocket("/management/leaderElection/events/ws?lockName=job&afterSequence=0") {
                hub.awaitSubscriberCount(1)
                val gap = withTimeout(5.seconds) { (incoming.receive() as Frame.Text).data.decodeToString() }
                gap shouldBeEqualTo "{\"event\":\"replay_gap\",\"from\":1,\"to\":1}"

                publisher.emit(LeaderElectionEvent.Skipped("other"))
                publisher.emit(LeaderElectionEvent.Elected("job", leaderId = "node-ws"))
                val event = withTimeout(5.seconds) { (incoming.receive() as Frame.Text).data.decodeToString() }
                event shouldContain "\"sequence\":5"
                event shouldContain "\"lockName\":\"job\""
                event shouldContain "\"leaderId\":\"node-ws\""
                close(CloseReason(CloseReason.Codes.NORMAL, "test"))
            }
            wsClient.close()
            publisher.emit(LeaderElectionEvent.Skipped("job"))
            withTimeout(5.seconds) {
                while (hub.subscriberCount() != 0) yield()
            }
        }
    }

    private class PublishingElector : SuspendLeaderElector, LeaderElectionEventPublisher {
        private val source = MutableSharedFlow<LeaderElectionEvent>(extraBufferCapacity = 64)

        override val events: Flow<LeaderElectionEvent> = source.asSharedFlow()

        override fun state(lockName: String): io.bluetape4k.leader.LeaderState =
            io.bluetape4k.leader.LeaderState.empty(lockName)

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()

        suspend fun emit(event: LeaderElectionEvent) {
            source.emit(event)
        }
    }

    private class NonPublishingElector : SuspendLeaderElector {
        override fun state(lockName: String): io.bluetape4k.leader.LeaderState =
            io.bluetape4k.leader.LeaderState.empty(lockName)

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }

    private suspend fun readSseFrame(channel: io.ktor.utils.io.ByteReadChannel): String {
        val lines = mutableListOf<String>()
        while (true) {
            val line = channel.readLine() ?: break
            if (line.isEmpty()) break
            lines += line
        }
        return lines.joinToString("\n")
    }

    private suspend fun awaitReplaySequence(hub: LeaderEventStreamHub, sequence: Long) {
        withTimeout(5.seconds) {
            while (hub.replay(afterSequence = null)
                    .filterIsInstance<LeaderStreamItem.Event>()
                    .none { it.sequence == sequence }
            ) {
                yield()
            }
        }
    }

    private fun basicAuth(username: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray())
}
