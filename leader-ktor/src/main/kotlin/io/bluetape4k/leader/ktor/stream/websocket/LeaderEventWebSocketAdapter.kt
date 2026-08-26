package io.bluetape4k.leader.ktor.stream.websocket

import io.bluetape4k.leader.ktor.LeaderElectionConfigurationException
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamConfig
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamPayload
import io.bluetape4k.leader.ktor.stream.LeaderStreamItem
import io.bluetape4k.leader.ktor.stream.installLeaderEventStreamPreflight
import io.ktor.server.application.MissingApplicationPluginException
import io.ktor.server.routing.Route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.Frame
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional Ktor WebSocket transport adapter loaded by the dependency-light bootstrap. */
public object LeaderEventWebSocketAdapter {

    private val connectionKey = AttributeKey<LeaderEventStreamHub.LeaderEventStreamConnection>(
        "io.bluetape4k.leader.ktor.stream.websocket.Connection",
    )

    @JvmStatic
    @JvmName("install")
    internal fun install(
        route: Route,
        hub: LeaderEventStreamHub,
        config: LeaderEventStreamConfig,
    ): Route {
        try {
            route.plugin(WebSockets)
        } catch (_: MissingApplicationPluginException) {
            throw LeaderElectionConfigurationException(
                "event stream WebSocket transport requires install(WebSockets)",
            )
        }

        val streamRoute = route.webSocket(webSocketPath(config.eventStreamRoutePath)) {
            val connection = call.attributes.getOrNull(connectionKey) ?: return@webSocket
            val heartbeatJob = launch {
                while (isActive) {
                    kotlinx.coroutines.delay(config.eventStreamHeartbeat)
                    send(Frame.Text(LeaderEventStreamPayload.heartbeat()))
                }
            }
            try {
                for (item in connection.channel) {
                    send(Frame.Text(item.toWebSocketPayload(config)))
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
            acceptsLastEventId = false,
        )
    }
}

private fun webSocketPath(path: String): String =
    path.trimEnd('/').ifEmpty { "" } + "/ws"

private fun LeaderStreamItem.toWebSocketPayload(config: LeaderEventStreamConfig): String =
    when (this) {
        is LeaderStreamItem.Event -> LeaderEventStreamPayload.event(event, sequence, config)
        is LeaderStreamItem.Control -> when (control) {
            LeaderStreamItem.Kind.HEARTBEAT -> LeaderEventStreamPayload.heartbeat()
            LeaderStreamItem.Kind.REPLAY_GAP -> LeaderEventStreamPayload.replayGap(
                requireNotNull(from),
                requireNotNull(to),
            )
            LeaderStreamItem.Kind.EVENT -> error("EVENT cannot be a control item")
        }
    }
