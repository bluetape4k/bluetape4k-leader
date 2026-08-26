@file:Suppress("ThrowsCount", "TooGenericExceptionCaught")

package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.ktor.stream.LeaderEventStreamConfig
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub
import io.ktor.server.application.Application
import io.ktor.server.application.MissingApplicationPluginException
import io.ktor.server.application.Plugin
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event stream adapter class names are kept as strings so the core Ktor plugin can be
 * loaded without optional SSE/WebSocket artifacts on the application classpath.
 */
private const val SSE_ADAPTER_CLASS =
    "io.bluetape4k.leader.ktor.stream.sse.LeaderEventSseAdapter"
private const val WEBSOCKET_ADAPTER_CLASS =
    "io.bluetape4k.leader.ktor.stream.websocket.LeaderEventWebSocketAdapter"

/** Optional adapter/bootstrap failure normalized to a stable configuration exception. */
internal class LeaderElectionConfigurationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Runtime state owned by the application plugin and discovered by the route registrar. */
internal data class LeaderEventStreamRuntime(
    val hub: LeaderEventStreamHub,
    val config: LeaderEventStreamConfig,
    val routeRegistered: AtomicBoolean = AtomicBoolean(false),
)

internal class LeaderEventStreamRuntimePluginConfig {
    var runtime: LeaderEventStreamRuntime? = null
}

/**
 * A dependency-light application plugin used as the route-builder handoff boundary.
 * `Route.plugin(...)` can discover application plugins through the routing root without
 * exposing `Application` or importing an optional transport API in the registrar.
 */
internal object LeaderEventStreamRuntimePlugin :
    Plugin<Application, LeaderEventStreamRuntimePluginConfig, LeaderEventStreamRuntime> {

    override val key: AttributeKey<LeaderEventStreamRuntime> =
        AttributeKey("io.bluetape4k.leader.ktor.LeaderEventStreamRuntime")

    override fun install(
        pipeline: Application,
        configure: LeaderEventStreamRuntimePluginConfig.() -> Unit,
    ): LeaderEventStreamRuntime =
        LeaderEventStreamRuntimePluginConfig().apply(configure).runtime
            ?: throw LeaderElectionConfigurationException(
                "event stream runtime plugin requires an application-owned runtime",
            )
}

/**
 * Registers the configured event stream under the caller's current authorization route.
 *
 * The plugin never creates a root route automatically: callers should invoke this inside
 * `authenticate { ... }` (or an equivalent authorization boundary). Exactly one registrar
 * invocation is accepted for an enabled plugin.
 */
public fun Route.leaderElectionEventStream(): Route {
    val runtime = try {
        plugin(LeaderEventStreamRuntimePlugin)
    } catch (_: MissingApplicationPluginException) {
        throw LeaderElectionConfigurationException(
            "event stream is disabled or LeaderElectionPlugin is not installed",
        )
    }

    if (!runtime.config.eventStreamRouteEnabled) {
        throw LeaderElectionConfigurationException(
            "event stream route registration requires eventStreamRouteEnabled=true",
        )
    }
    if (!runtime.routeRegistered.compareAndSet(false, true)) {
        throw LeaderElectionConfigurationException(
            "event stream route registrar may be called only once",
        )
    }

    try {
        if (runtime.config.eventStreamSseEnabled) {
            invokeAdapter(SSE_ADAPTER_CLASS, this, runtime)
        }
        if (runtime.config.eventStreamWebSocketEnabled) {
            invokeAdapter(WEBSOCKET_ADAPTER_CLASS, this, runtime)
        }
    } catch (failure: Throwable) {
        runtime.routeRegistered.set(false)
        throw normalizeAdapterFailure(failure)
    }
    return this
}

private fun invokeAdapter(
    className: String,
    route: Route,
    runtime: LeaderEventStreamRuntime,
) {
    val classLoader = Thread.currentThread().contextClassLoader
        ?: LeaderEventStreamBootstrap::class.java.classLoader
    val adapter = Class.forName(className, true, classLoader)
    val method = adapter.methods.firstOrNull { candidate ->
        candidate.name == "install" &&
            Modifier.isStatic(candidate.modifiers) &&
            candidate.parameterTypes.contentEquals(
                arrayOf(Route::class.java, LeaderEventStreamHub::class.java, LeaderEventStreamConfig::class.java),
            )
    } ?: throw NoSuchMethodException("install(Route, LeaderEventStreamHub, LeaderEventStreamConfig)")

    method.invoke(null, route, runtime.hub, runtime.config)
}

private fun normalizeAdapterFailure(failure: Throwable): LeaderElectionConfigurationException {
    val cause = when (failure) {
        is InvocationTargetException -> failure.targetException ?: failure
        else -> failure
    }
    if (cause is LeaderElectionConfigurationException) return cause
    return LeaderElectionConfigurationException(
        "event stream transport adapter is unavailable or misconfigured",
        cause,
    )
}

/** Marker used only to keep the bootstrap's class name available to reflection tests. */
private object LeaderEventStreamBootstrap
