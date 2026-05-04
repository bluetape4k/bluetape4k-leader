package io.bluetape4k.leader.hazelcast

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.HazelcastServer
import io.bluetape4k.utils.ShutdownQueue
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractHazelcastLeaderTest {

    companion object: KLogging() {
        val hazelcastServer: HazelcastServer = HazelcastServer.Launcher.hazelcast

        val hazelcastClient: HazelcastInstance by lazy {
            val config = ClientConfig().apply {
                networkConfig.addAddress(hazelcastServer.url)
            }
            HazelcastClient.newHazelcastClient(config).also {
                ShutdownQueue.register { it.shutdown() }
            }
        }
    }

    fun randomName(): String = "leader-test:${Base58.randomString(8)}"
}
