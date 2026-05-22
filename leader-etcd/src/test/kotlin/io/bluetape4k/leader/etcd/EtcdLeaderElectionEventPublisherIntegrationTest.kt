package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class EtcdLeaderElectionEventPublisherIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `publisher emits elected and revoked events for single leader ownership`() = runSuspendIO {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val publisher = EtcdLeaderElectionEventPublisher(client, keyPrefix)
            val elector = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(keyPrefix = keyPrefix),
            )
            val lockName = randomName()

            publisher.use {
                val events = async {
                    publisher.events.take(2).toList()
                }
                delay(250)

                elector.runIfLeader(lockName) {
                    "done"
                } shouldBeEqualTo "done"

                withTimeout(10.seconds) {
                    events.await()
                } shouldBeEqualTo listOf(
                    LeaderElectionEvent.Elected(lockName),
                    LeaderElectionEvent.Revoked(lockName),
                )
            }
        }
    }

    @Test
    fun `publisher emits elected and revoked events for group slot ownership`() = runSuspendIO {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val publisher = EtcdLeaderElectionEventPublisher(client, keyPrefix)
            val elector = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                    keyPrefix = keyPrefix,
                ),
            )
            val lockName = randomName()

            publisher.use {
                val events = async {
                    publisher.events.take(2).toList()
                }
                delay(250)

                elector.runIfLeader(lockName) {
                    "done"
                } shouldBeEqualTo "done"

                withTimeout(10.seconds) {
                    events.await()
                } shouldBeEqualTo listOf(
                    LeaderElectionEvent.Elected(lockName),
                    LeaderElectionEvent.Revoked(lockName),
                )
            }
        }
    }

    @Test
    fun `publisher does not emit elected for queued single contenders`() = runSuspendIO {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val publisher = EtcdLeaderElectionEventPublisher(client, keyPrefix)
            val elector = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(keyPrefix = keyPrefix),
            )
            val lockName = randomName()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                publisher.use {
                    val events = async {
                        publisher.events.take(4).toList()
                    }
                    delay(250)

                    val holder = executor.submit<String?> {
                        elector.runIfLeader(lockName) {
                            started.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            "holder"
                        }
                    }

                    started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
                    val contender = executor.submit<String?> {
                        elector.runIfLeader(lockName) {
                            "contender"
                        }
                    }
                    delay(500)

                    release.countDown()
                    holder.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
                    contender.get(10, TimeUnit.SECONDS) shouldBeEqualTo "contender"

                    withTimeout(10.seconds) {
                        events.await()
                    }.map { event -> event.name } shouldBeEqualTo listOf("elected", "revoked", "elected", "revoked")
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `closing publisher does not close caller owned client`() = runSuspendIO {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            EtcdLeaderElectionEventPublisher(client, keyPrefix).close()

            val elector = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(keyPrefix = keyPrefix),
            )

            elector.runIfLeader(randomName()) {
                "still-open"
            } shouldBeEqualTo "still-open"
        }
    }

    private val LeaderElectionEvent.name: String
        get() = when (this) {
            is LeaderElectionEvent.Elected -> "elected"
            is LeaderElectionEvent.Revoked -> "revoked"
            is LeaderElectionEvent.Skipped -> "skipped"
        }
}
