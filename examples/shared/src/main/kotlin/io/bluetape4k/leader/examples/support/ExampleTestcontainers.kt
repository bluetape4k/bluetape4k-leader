package io.bluetape4k.leader.examples.support

import io.bluetape4k.utils.ShutdownQueue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.TestcontainersConfiguration

private val CI_MARKERS = setOf("CI", "GITHUB_ACTIONS")

internal fun developerLocalReuseEnabled(
    environment: Map<String, String> = System.getenv(),
    environmentSupportsReuse: () -> Boolean = {
        TestcontainersConfiguration.getInstance().environmentSupportsReuse()
    },
): Boolean =
    environment.keys.none(CI_MARKERS::contains) && environmentSupportsReuse()

internal fun <T: GenericContainer<*>> startExampleContainer(
    createContainer: (reuse: Boolean) -> T,
): T {
    val reuse = developerLocalReuseEnabled()
    return startExampleContainer(
        reuse = reuse,
        createContainer = createContainer,
        start = { start() },
        registerForShutdown = { ShutdownQueue.register(it) },
    )
}

internal fun <T> startExampleContainer(
    reuse: Boolean,
    createContainer: (reuse: Boolean) -> T,
    start: T.() -> Unit,
    registerForShutdown: (T) -> Unit,
): T =
    createContainer(reuse).apply {
        start()
        if (!reuse) {
            registerForShutdown(this)
        }
    }
