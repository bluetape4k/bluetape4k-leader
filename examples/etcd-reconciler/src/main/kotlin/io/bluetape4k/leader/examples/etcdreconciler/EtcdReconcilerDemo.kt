package io.bluetape4k.leader.examples.etcdreconciler

import io.bluetape4k.leader.examples.support.startExampleContainer
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.etcd.jetcd.Client
import java.time.Duration

/**
 * `EtcdReconcilerDemo`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
object EtcdReconcilerDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        startExampleContainer { reuse -> EtcdServer(reuse = reuse) }
            .also { etcd ->
                Client.builder()
                    .endpoints(etcd.endpoint)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .use { client ->
                        val reconciler = ControlPlaneReconciler(
                            nodeId = "control-plane-a",
                            client = client,
                            lockName = "control-plane-reconcile",
                        )
                        val report = reconciler.reconcile {
                            listOf("deployment/api", "configmap/routing", "service/api")
                        }
                        println(report)
                    }
            }
    }
}
