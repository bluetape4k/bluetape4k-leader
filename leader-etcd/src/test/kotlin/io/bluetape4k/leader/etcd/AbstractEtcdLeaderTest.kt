package io.bluetape4k.leader.etcd

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.etcd.jetcd.Client
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.time.Duration

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractEtcdLeaderTest {

    companion object: KLogging() {
        val etcd: EtcdServer = EtcdServer.Launcher.etcd

        fun newClient(): Client =
            Client.builder()
                .endpoints(etcd.endpoint)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
    }

    protected fun randomName(): String = "leader-test:${Base58.randomString(8)}"
}
