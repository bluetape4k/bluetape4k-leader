package io.bluetape4k.leader.etcd

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.testcontainers.ReadinessEndpoint
import io.bluetape4k.leader.testcontainers.readinessBoundaryWaitStrategy
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.bluetape4k.utils.ShutdownQueue
import io.etcd.jetcd.Client
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.time.Duration

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractEtcdLeaderTest {

    companion object: KLogging() {
        private val ETCD_READINESS_ENDPOINT =
            ReadinessEndpoint(EtcdServer.NAME, EtcdServer.CLIENT_PORT, "/health")

        val etcd: EtcdServer = EtcdServer(reuse = false).apply {
            waitingFor(readinessBoundaryWaitStrategy(ETCD_READINESS_ENDPOINT))
            start()
            ShutdownQueue.register(this)
        }

        fun newClient(): Client =
            Client.builder()
                .endpoints(etcd.endpoint)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
    }

    protected fun randomName(): String = "leader-test:${Base58.randomString(8)}"
}
