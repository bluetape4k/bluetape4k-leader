package io.bluetape4k.leader.examples.etcdreconciler

import io.bluetape4k.testcontainers.infra.EtcdServer
import io.etcd.jetcd.Client
import java.time.Duration

/**
 * Runnable entrypoint for the etcd-backed control-plane reconciler example.
 */
object EtcdReconcilerDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        EtcdServer.Launcher.etcd.also { etcd ->
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
