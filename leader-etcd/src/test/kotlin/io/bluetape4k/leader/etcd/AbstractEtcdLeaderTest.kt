package io.bluetape4k.leader.etcd

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.leader.etcd.support.etcdClientOf
import io.bluetape4k.leader.testcontainers.ReadinessEndpoint
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.etcd.jetcd.Client
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractEtcdLeaderTest {

    companion object: KLogging() {

        val ETCD_READINESS_ENDPOINT =
            ReadinessEndpoint(EtcdServer.NAME, EtcdServer.CLIENT_PORT, "/health")

        val etcdServer by lazy { EtcdServer.Launcher.etcd }

        fun newClient(): Client = etcdClientOf(
            endpoint = etcdServer.endpoint,
            connectTimeout = 10.seconds
        )

        @JvmStatic
        protected val faker get() = Fakers.faker

        @JvmStatic
        protected val defaultFaker get() = Fakers.defaultFaker
    }

    protected fun randomName(): String = "leader-test:${Base58.randomString(8)}"
}
