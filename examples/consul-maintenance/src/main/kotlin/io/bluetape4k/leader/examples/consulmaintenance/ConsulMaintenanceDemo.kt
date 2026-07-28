package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.examples.support.startExampleContainer
import io.bluetape4k.testcontainers.infra.ConsulServer

/**
 * `ConsulMaintenanceDemo`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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
