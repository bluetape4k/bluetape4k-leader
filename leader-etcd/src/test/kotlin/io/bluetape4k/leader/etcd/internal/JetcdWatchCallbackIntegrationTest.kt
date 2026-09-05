package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.etcd.AbstractEtcdLeaderTest
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class JetcdWatchCallbackIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `callback can perform blocking kv get`() {
        newClient().use { client ->
            val key = byteSequence("/bluetape4k/leader/test/${randomName()}/callback")
            val expected = "callback-value"
            val callbackValue = CompletableFuture<String>()
            val watcher = client.readyWatcher(
                key = key,
                onEvent = { event ->
                    if (event.eventType == WatchEvent.EventType.PUT) {
                        val response = client.kvClient.get(key).get(3, TimeUnit.SECONDS)
                        callbackValue.complete(response.kvs.single().value.asString())
                    }
                },
                onFailure = callbackValue::completeExceptionally,
            )

            watcher.use {
                watcher.awaitReady()
                client.kvClient.put(key, byteSequence(expected)).get(10, TimeUnit.SECONDS)

                callbackValue.get(10, TimeUnit.SECONDS) shouldBeEqualTo expected
            }
        }
    }

    @Test
    fun `slow first callback preserves put and delete order`() {
        newClient().use { client ->
            val key = byteSequence("/bluetape4k/leader/test/${randomName()}/ordered")
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val allEvents = CountDownLatch(3)
            val observed = CopyOnWriteArrayList<String>()
            val failure = CompletableFuture<Unit>()
            val watcher = client.readyWatcher(
                key = key,
                onEvent = { event ->
                    if (event.eventType == WatchEvent.EventType.PUT && observed.isEmpty()) {
                        firstEntered.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                    observed += event.label()
                    allEvents.countDown()
                },
                onFailure = failure::completeExceptionally,
            )

            try {
                watcher.awaitReady()
                client.kvClient.put(key, byteSequence("v1")).get(10, TimeUnit.SECONDS)
                firstEntered.await(10, TimeUnit.SECONDS).shouldBeTrue()
                client.kvClient.put(key, byteSequence("v2")).get(10, TimeUnit.SECONDS)
                client.kvClient.delete(key).get(10, TimeUnit.SECONDS)
                releaseFirst.countDown()

                allEvents.await(10, TimeUnit.SECONDS).shouldBeTrue()
                failure.isDone.shouldBeFalse()
                observed shouldBeEqualTo listOf("PUT:v1", "PUT:v2", "DELETE")
            } finally {
                releaseFirst.countDown()
                watcher.close()
            }
        }
    }

    @Test
    fun `closed watcher stops delivery and a new watcher resumes`() {
        newClient().use { client ->
            val key = byteSequence("/bluetape4k/leader/test/${randomName()}/restart")
            val closedWatcherEvent = CompletableFuture<String>()
            val firstWatcher = client.readyWatcher(
                key = key,
                onEvent = { event -> closedWatcherEvent.complete(event.label()) },
                onFailure = closedWatcherEvent::completeExceptionally,
            )

            firstWatcher.awaitReady()
            firstWatcher.close()
            client.kvClient.put(key, byteSequence("after-close")).get(10, TimeUnit.SECONDS)

            assertFailsWith<TimeoutException> {
                closedWatcherEvent.get(500, TimeUnit.MILLISECONDS)
            }

            val restartedWatcherEvent = CompletableFuture<String>()
            val restartedWatcher = client.readyWatcher(
                key = key,
                onEvent = { event -> restartedWatcherEvent.complete(event.label()) },
                onFailure = restartedWatcherEvent::completeExceptionally,
            )
            restartedWatcher.use {
                restartedWatcher.awaitReady()
                client.kvClient.put(key, byteSequence("after-restart")).get(10, TimeUnit.SECONDS)

                restartedWatcherEvent.get(10, TimeUnit.SECONDS) shouldBeEqualTo "PUT:after-restart"
            }
        }
    }

    private fun Client.readyWatcher(
        key: ByteSequence,
        onEvent: (WatchEvent) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): ReadyWatcher {
        val ready = CompletableFuture<Unit>()
        val option = WatchOption.builder()
            .withCreateNotify(true)
            .build()
        val watcher = watchClient.watch(
            key,
            option,
            Watch.listener(
                { response ->
                    if (response.isCreatedNotify) {
                        ready.complete(Unit)
                    } else {
                        runCatching { response.events.forEach(onEvent) }
                            .onFailure(onFailure)
                    }
                },
                { error ->
                    ready.completeExceptionally(error)
                    onFailure(error)
                },
            ),
        )
        return ReadyWatcher(watcher, ready)
    }

    private fun WatchEvent.label(): String =
        when (eventType) {
            WatchEvent.EventType.PUT -> "PUT:${keyValue.value.asString()}"
            WatchEvent.EventType.DELETE -> "DELETE"
            WatchEvent.EventType.UNRECOGNIZED -> "UNRECOGNIZED"
        }

    private fun byteSequence(value: String): ByteSequence =
        ByteSequence.from(value, StandardCharsets.UTF_8)

    private fun ByteSequence.asString(): String = toString(StandardCharsets.UTF_8)

    private class ReadyWatcher(
        private val delegate: Watch.Watcher,
        private val ready: CompletableFuture<Unit>,
    ): AutoCloseable {

        fun awaitReady() {
            ready.get(10, TimeUnit.SECONDS)
        }

        override fun close() {
            delegate.close()
        }
    }
}
