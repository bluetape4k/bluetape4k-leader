package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.client.KubernetesClient

internal object KubernetesContractSupport {
    private val k3s: K3sServer by lazy { K3sServer.Launcher.k3s }

    fun newClient(): KubernetesClient = k3s.kubernetesClient()
}
