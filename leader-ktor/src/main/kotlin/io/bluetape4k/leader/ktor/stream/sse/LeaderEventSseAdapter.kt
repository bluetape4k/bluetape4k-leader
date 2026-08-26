package io.bluetape4k.leader.ktor.stream.sse

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.ktor.LeaderElectionConfigurationException
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamConfig
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamPayload
import io.bluetape4k.leader.ktor.stream.LeaderStreamItem
import io.bluetape4k.leader.ktor.stream.installLeaderEventStreamPreflight
import io.ktor.server.application.MissingApplicationPluginException
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** Optional Ktor SSE transport adapter loaded by the dependency-light bootstrap. */
public object LeaderEventSseAdapter {

    private val connectionKey = AttributeKey<LeaderEventStreamHub.LeaderEventStreamConnection>(
        "io.bluetape4k.leader.ktor.stream.sse.Connection",
    )

    @JvmStatic
    @JvmName("install")
    internal fun install(
        route: Route,
        hub: LeaderEventStreamHub,
        config: LeaderEventStreamConfig,
    ): Route {
        try {
            route.plugin(SSE)
        } catch (_: MissingApplicationPluginException) {
            throw LeaderElectionConfigurationException(
                "event stream SSE transport requires install(SSE)",
            )
        }

        val streamRoute = route.sse(config.eventStreamRoutePath) {
            val connection = call.attributes.getOrNull(connectionKey) ?: return@sse
            val heartbeatJob = launch {
                while (isActive) {
                    kotlinx.coroutines.delay(config.eventStreamHeartbeat)
                    send(
                        ServerSentEvent(
                            data = LeaderEventStreamPayload.heartbeat(),
                            event = "heartbeat",
                        ),
                    )
                }
            }
            try {
                for (item in connection.channel) {
                    send(item.toServerSentEvent(config))
                }
            } finally {
                withContext(NonCancellable) {
                    heartbeatJob.cancelAndJoin()
                    hub.releaseConnection(connection)
                }
            }
        }

        return streamRoute.installLeaderEventStreamPreflight(
            hub = hub,
            config = config,
            connectionKey = connectionKey,
            acceptsLastEventId = true,
        )
    }
}

private fun LeaderStreamItem.toServerSentEvent(config: LeaderEventStreamConfig): ServerSentEvent =
    when (this) {
        is LeaderStreamItem.Event -> ServerSentEvent(
            data = LeaderEventStreamPayload.event(event, sequence, config),
            event = event.typeName(),
            id = sequence.toString(),
        )
        is LeaderStreamItem.Control -> when (control) {
            LeaderStreamItem.Kind.HEARTBEAT -> ServerSentEvent(
                data = LeaderEventStreamPayload.heartbeat(),
                event = "heartbeat",
            )
            LeaderStreamItem.Kind.REPLAY_GAP -> ServerSentEvent(
                data = LeaderEventStreamPayload.replayGap(requireNotNull(from), requireNotNull(to)),
                event = "replay_gap",
            )
            LeaderStreamItem.Kind.EVENT -> error("EVENT cannot be a control item")
        }
    }

private fun LeaderElectionEvent.typeName(): String = when (this) {
    is LeaderElectionEvent.Elected -> "Elected"
    is LeaderElectionEvent.Revoked -> "Revoked"
    is LeaderElectionEvent.Skipped -> "Skipped"
}
