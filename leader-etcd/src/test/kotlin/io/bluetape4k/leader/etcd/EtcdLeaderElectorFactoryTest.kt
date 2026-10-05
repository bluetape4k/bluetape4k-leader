package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.etcd.jetcd.Client
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderElectorFactoryTest {

    private val client = mockk<Client>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(client)
    }

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
    fun `group factories merge call options into base etcd options`() = runSuspendIO {
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

        val suspendElector =
            EtcdSuspendLeaderGroupElectorFactory(client, baseOptions)
                .create(groupOptions)
                .shouldBeInstanceOf<EtcdSuspendLeaderGroupElector>()

        suspendElector.options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        suspendElector.options.leaderGroupOptions shouldBeEqualTo groupOptions

        verify(exactly = 0) { client.close() }
    }

    @Test
    fun `virtual client extension rejects invalid lock name before submission`() {
        val actionInvoked = AtomicBoolean(false)

        assertFailsWith<IllegalArgumentException> {
            client.runVirtualIfLeader("invalid lock") {
                actionInvoked.set(true)
            }
        }

        actionInvoked.get().shouldBeFalse()
    }
}
