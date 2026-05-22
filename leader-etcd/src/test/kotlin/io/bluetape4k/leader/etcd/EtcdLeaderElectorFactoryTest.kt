package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.etcd.jetcd.Client
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderElectorFactoryTest {

    private val client = mockk<Client>(relaxed = true)

    @Test
    fun `single leader factories merge call options into base etcd options`() {
        val baseOptions = EtcdLeaderElectionOptions(keyPrefix = "/apps/orders/leader")
        val leaderOptions = LeaderElectionOptions(
            nodeId = "node-a",
            waitTime = 2.seconds,
            leaseTime = 30.seconds,
            autoExtend = true,
        )

        val blockingElector = EtcdLeaderElectorFactory(client, baseOptions)
            .create(leaderOptions)
            .shouldBeInstanceOf<EtcdLeaderElector>()

        blockingElector.options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        blockingElector.options.leaderOptions shouldBeEqualTo leaderOptions

        val suspendElector = runBlocking {
            EtcdSuspendLeaderElectorFactory(client, baseOptions)
                .create(leaderOptions)
                .shouldBeInstanceOf<EtcdSuspendLeaderElector>()
        }

        suspendElector.options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        suspendElector.options.leaderOptions shouldBeEqualTo leaderOptions
        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `group factories merge call options into base etcd options`() {
        val baseOptions = EtcdLeaderGroupElectionOptions(keyPrefix = "/apps/orders/leader")
        val groupOptions = LeaderGroupElectionOptions(
            maxLeaders = 3,
            waitTime = 2.seconds,
            leaseTime = 30.seconds,
        )

        val blockingElector = EtcdLeaderGroupElectorFactory(client, baseOptions)
            .create(groupOptions)
            .shouldBeInstanceOf<EtcdLeaderGroupElector>()

        blockingElector.options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        blockingElector.options.leaderGroupOptions shouldBeEqualTo groupOptions

        val suspendElector = runBlocking {
            EtcdSuspendLeaderGroupElectorFactory(client, baseOptions)
                .create(groupOptions)
                .shouldBeInstanceOf<EtcdSuspendLeaderGroupElector>()
        }

        suspendElector.options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        suspendElector.options.leaderGroupOptions shouldBeEqualTo groupOptions
        verify(exactly = 0) { client.close() }
    }
}
