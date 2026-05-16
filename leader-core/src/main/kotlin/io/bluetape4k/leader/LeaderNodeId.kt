package io.bluetape4k.leader

import java.lang.management.ManagementFactory
import java.net.InetAddress

/**
 * Provides the default leader node identifier.
 *
 * ## Contract
 * [Default] is a stable id computed once per JVM process. Generating a random id on each options
 * default call would destabilize equality and cache keys across identical configurations,
 * so the default must remain constant for the lifetime of the process.
 */
object LeaderNodeId {

    /**
     * Default node identifier for the current JVM process.
     *
     * Format is `host:pid`; falls back to `localhost` if host resolution fails.
     */
    @JvmField
    val Default: String = "${hostname()}:${ProcessHandle.current().pid()}"

    private fun hostname(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .recoverCatching { ManagementFactory.getRuntimeMXBean().name.substringBefore('@') }
            .getOrDefault("localhost")
}
