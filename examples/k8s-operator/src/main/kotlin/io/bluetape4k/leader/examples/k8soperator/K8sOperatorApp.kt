package io.bluetape4k.leader.examples.k8soperator

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.logging.KLogging
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
class K8sOperatorApp {

    companion object: KLogging()

    @Bean(destroyMethod = "close")
    fun kubernetesClient(): KubernetesClient =
        KubernetesClientBuilder().build()

    @Bean
    fun leaderElector(
        client: KubernetesClient,
        @Value($$"${demo.operator.namespace:default}") namespace: String,
        @Value($$"${demo.operator.node-id:\${HOSTNAME:local-operator}}") nodeId: String,
        @Value($$"${demo.operator.wait-time:PT1S}") waitTime: java.time.Duration,
        @Value($$"${demo.operator.lease-time:PT30S}") leaseTime: java.time.Duration,
    ): LeaderElector =
        KubernetesLeaseLeaderElector(
            client,
            KubernetesLeaseOptions(
                namespace = namespace,
                leaderOptions = LeaderElectionOptions(
                    waitTime = waitTime.toKotlinDuration(),
                    leaseTime = leaseTime.toKotlinDuration(),
                    nodeId = nodeId,
                    autoExtend = true,
                ),
                retryDelay = 1.seconds,
            ),
        )
}

fun main(args: Array<String>) {
    runApplication<K8sOperatorApp>(*args)
}
