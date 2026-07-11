package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.testcontainers.infra.ConsulServer
import io.bluetape4k.utils.ShutdownQueue
import org.testcontainers.utility.TestcontainersConfiguration

/**
 * Runnable entrypoint for the Consul-backed service maintenance example.
 */
object ConsulMaintenanceDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val consul = ConsulServer(reuse = developerLocalReuseEnabled()).apply {
            start()
            ShutdownQueue.register(this)
        }
        val endpoint = ConsulEndpoint(consul.url)
        val reports = ServiceMaintenanceScenario(endpoint).run()

        reports.forEach { report ->
            println(report)
        }
    }

    private fun developerLocalReuseEnabled(): Boolean =
        System.getenv("CI") != "true" && TestcontainersConfiguration.getInstance().environmentSupportsReuse()
}
