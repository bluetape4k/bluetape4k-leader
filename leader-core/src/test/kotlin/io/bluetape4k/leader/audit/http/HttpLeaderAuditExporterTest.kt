package io.bluetape4k.leader.audit.http

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpHeaders
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.PushPromiseHandler
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.time.Duration.Companion.seconds

class HttpLeaderAuditExporterTest {

    private val schedulers = mutableListOf<ScheduledExecutorService>()

    @AfterEach
    fun tearDown() {
        schedulers.forEach { it.shutdownNow() }
    }

    @Test
    fun `exporter delegates bounded admission and HTTP completion`() {
        val responseFuture = CompletableFuture<HttpResponse<Void>>()
        val client = StubHttpClient(responseFuture)
        val exporter = exporter(client)

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        client.requestReady.await(5, TimeUnit.SECONDS).shouldBeTrue()
        client.request.shouldNotBeNull().uri().shouldBeEqualTo(URI("https://audit.example.test/hook"))
        responseFuture.complete(response(202))
        awaitAdmissionReleased(exporter)
        exporter.snapshot().accepted.shouldBeEqualTo(1)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.close()
    }

    @Test
    fun `close cancels in flight request and late completion cannot schedule retry`() {
        val responseFuture = CompletableFuture<HttpResponse<Void>>()
        val cancellationObserved = CountDownLatch(1)
        responseFuture.whenComplete { _, failure ->
            if (failure is CancellationException) cancellationObserved.countDown()
        }
        val client = StubHttpClient(responseFuture, blockSendAsyncReturn = true)
        val exporter = exporter(client)

        val submitResult = CompletableFuture<LeaderAuditSubmitResult>()
        Thread.ofVirtual().start {
            try {
                submitResult.complete(exporter.submit(event()))
            } catch (error: Throwable) {
                submitResult.completeExceptionally(error)
            }
        }
        client.requestReady.await(5, TimeUnit.SECONDS).shouldBeTrue()
        val closeReturned = CountDownLatch(1)
        val closeResult = CompletableFuture<Unit>()
        Thread.ofVirtual().start {
            try {
                exporter.close()
                closeResult.complete(Unit)
            } catch (error: Throwable) {
                closeResult.completeExceptionally(error)
            } finally {
                closeReturned.countDown()
            }
        }
        try {
            closeReturned.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            client.sendAsyncReturnAllowed.countDown()
        }
        closeResult.get(5, TimeUnit.SECONDS).shouldBeEqualTo(Unit)
        submitResult.get(5, TimeUnit.SECONDS).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        cancellationObserved.await(5, TimeUnit.SECONDS).shouldBeTrue()
        responseFuture.isCancelled.shouldBeTrue()
        responseFuture.complete(response(503)).shouldBeFalse()
        awaitCancellationRecorded(exporter)

        val snapshot = exporter.snapshot()
        snapshot.closed.shouldBeTrue()
        snapshot.scheduledRetries.shouldBeEqualTo(0)
        snapshot.admitted.shouldBeEqualTo(0)
        snapshot.cancellations.shouldBeEqualTo(1)
    }

    private fun exporter(client: HttpClient): HttpLeaderAuditExporter = HttpLeaderAuditExporter(
        client = client,
        endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(URI("https://audit.example.test/hook")),
        headers = mapOf("Authorization" to "Bearer ${WEBHOOK_TOKEN_PLACEHOLDER}"),
        encoder = LeaderAuditPayloadEncoder {
            LeaderAuditHttpPayload.of("text/plain", "audit".toByteArray())
        },
        exportOptions = LeaderAuditExportOptions(
            queueCapacity = 8,
            maxInFlight = 2,
            maxAttempts = 2,
            attemptTimeout = Duration.ofSeconds(1),
            initialBackoff = Duration.ofMillis(1),
            maxBackoff = Duration.ofSeconds(1),
            executor = Executor { it.run() },
            scheduler = Executors.newSingleThreadScheduledExecutor().also(schedulers::add),
        ),
        httpOptions = LeaderAuditHttpOptions.defaults(),
    )

    private fun event(): LeaderAuditExportEvent.History = LeaderAuditExportEvent.History.from(
        record = LeaderLockHistoryRecord(
            lockName = "lock",
            token = "token",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = java.time.Instant.parse("2026-08-18T00:00:00Z"),
            lockedUntil = java.time.Instant.parse("2026-08-18T00:01:00Z"),
            status = LeaderHistoryStatus.ACQUIRED,
        ),
        sanitizer = LeaderAuditValueSanitizer.Default,
    )

    private fun awaitAdmissionReleased(exporter: HttpLeaderAuditExporter) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (exporter.snapshot().admitted != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
    }

    private fun awaitCancellationRecorded(exporter: HttpLeaderAuditExporter) {
        await
            .atMost(5.seconds)
            .untilAsserted {
                exporter.snapshot().cancellations.shouldBeEqualTo(1)
            }
    }

    private class StubHttpClient(
        private val responseFuture: CompletableFuture<HttpResponse<Void>>,
        private val blockSendAsyncReturn: Boolean = false,
    ) : HttpClient() {
        val requestReady = CountDownLatch(1)
        val sendAsyncReturnAllowed = CountDownLatch(1)
        var request: HttpRequest? = null

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T> send(request: HttpRequest, responseBodyHandler: BodyHandler<T>): HttpResponse<T> {
            throw UnsupportedOperationException("send is not used by async delivery")
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            this.request = request
            requestReady.countDown()
            if (blockSendAsyncReturn) {
                sendAsyncReturnAllowed.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }
            return responseFuture as CompletableFuture<HttpResponse<T>>
        }

        override fun <T> sendAsync(
            request: HttpRequest,
            responseBodyHandler: BodyHandler<T>,
            pushPromiseHandler: PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private fun response(status: Int): HttpResponse<Void> = object : HttpResponse<Void> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest = HttpRequest.newBuilder(URI("https://audit.example.test/hook")).build()

        override fun previousResponse(): Optional<HttpResponse<Void>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): Void? = null

        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()

        override fun uri(): URI = URI("https://audit.example.test/hook")

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private companion object {
        const val WEBHOOK_TOKEN_PLACEHOLDER = "\${WEBHOOK_TOKEN}"
    }
}
