package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
            val elected = CountDownLatch(1)

            publisher.use {
                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.events
                        .onEach { event ->
                            if (event == LeaderElectionEvent.Elected(lockName)) elected.countDown()
                        }
                        .take(2)
                        .toList()
                }

                elector.runIfLeader(lockName) {
                    elected.await(10, TimeUnit.SECONDS).shouldBeTrue()
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
            val elected = CountDownLatch(1)

            publisher.use {
                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    publisher.events
                        .onEach { event ->
                            if (event == LeaderElectionEvent.Elected(lockName)) elected.countDown()
                        }
                        .take(2)
                        .toList()
                }

                elector.runIfLeader(lockName) {
                    elected.await(10, TimeUnit.SECONDS).shouldBeTrue()
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
            val holderElected = CountDownLatch(1)
            val contenderStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                publisher.use {
                    val events = async(start = CoroutineStart.UNDISPATCHED) {
                        publisher.events
                            .onEach { event ->
                                if (event == LeaderElectionEvent.Elected(lockName)) holderElected.countDown()
                            }
                            .take(4)
                            .toList()
                    }

                    val holder = executor.submit<String?> {
                        elector.runIfLeader(lockName) {
                            started.countDown()
                            release.await(10, TimeUnit.SECONDS)
                            "holder"
                        }
                    }

                    started.await(10, TimeUnit.SECONDS).shouldBeTrue()
                    holderElected.await(10, TimeUnit.SECONDS).shouldBeTrue()
                    val contender = executor.submit<String?> {
                        contenderStarted.countDown()
                        elector.runIfLeader(lockName) {
                            "contender"
                        }
                    }
                    contenderStarted.await(10, TimeUnit.SECONDS).shouldBeTrue()

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
            val elector = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(keyPrefix = keyPrefix),
            )
            val closedPublisher = EtcdLeaderElectionEventPublisher(client, keyPrefix)
            val unexpectedEvent = CompletableFuture<LeaderElectionEvent>()
            val closedCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                closedPublisher.events.collect { event -> unexpectedEvent.complete(event) }
            }

            closedPublisher.close()

            elector.runIfLeader(randomName()) {
                "still-open"
            } shouldBeEqualTo "still-open"

            assertFailsWith<TimeoutException> {
                unexpectedEvent.get(500, TimeUnit.MILLISECONDS)
            }
            closedCollector.cancelAndJoin()

            val lockName = randomName()
            val restartedPublisher = EtcdLeaderElectionEventPublisher(client, keyPrefix)
            val elected = CountDownLatch(1)
            restartedPublisher.use {
                val events = async(start = CoroutineStart.UNDISPATCHED) {
                    restartedPublisher.events
                        .onEach { event ->
                            if (event == LeaderElectionEvent.Elected(lockName)) elected.countDown()
                        }
                        .take(2)
                        .toList()
                }

                elector.runIfLeader(lockName) {
                    elected.await(10, TimeUnit.SECONDS).shouldBeTrue()
                    "restarted"
                } shouldBeEqualTo "restarted"

                withTimeout(10.seconds) {
                    events.await()
                } shouldBeEqualTo listOf(
                    LeaderElectionEvent.Elected(lockName),
                    LeaderElectionEvent.Revoked(lockName),
                )
            }
        }
    }

    private val LeaderElectionEvent.name: String
        get() = when (this) {
            is LeaderElectionEvent.Elected -> "elected"
            is LeaderElectionEvent.Revoked -> "revoked"
            is LeaderElectionEvent.Skipped -> "skipped"
        }
}
