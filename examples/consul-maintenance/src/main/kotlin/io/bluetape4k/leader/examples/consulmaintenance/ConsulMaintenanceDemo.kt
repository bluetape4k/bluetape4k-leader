package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.examples.support.startExampleContainer
import io.bluetape4k.testcontainers.infra.ConsulServer

/**
 * Runnable entrypoint for the Consul-backed service maintenance example.
 */
object ConsulMaintenanceDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val consul = startExampleContainer { reuse -> ConsulServer(reuse = reuse) }
        val endpoint = ConsulEndpoint(consul.url)
        val reports = ServiceMaintenanceScenario(endpoint).run()

        reports.forEach { report ->
            println(report)
        }
    }
}
