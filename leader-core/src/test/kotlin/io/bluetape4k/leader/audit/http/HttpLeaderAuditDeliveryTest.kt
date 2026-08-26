package io.bluetape4k.leader.audit.http

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.LeaderAuditDeliveryResult
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.PushPromiseHandler
import java.nio.ByteBuffer
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Flow
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

class HttpLeaderAuditDeliveryTest {

    private val schedulers = mutableListOf<ScheduledExecutorService>()

    @AfterEach
    fun tearDown() {
        schedulers.forEach { it.shutdownNow() }
    }

    @Test
    fun `success statuses are mapped without retaining response body`() {
        listOf(200, 202, 204, 299).forEach { status ->
            val responseFuture = CompletableFuture<HttpResponse<Void>>()
            val client = StubHttpClient(responseFuture)
            val delivery = delivery(client)

            val result = delivery.deliver(event())
            client.bodyHandler.shouldNotBeNull()
            val capturedBodyHandler = client.bodyHandler
            capturedBodyHandler.shouldNotBeNull()
            assertDiscardingBodyHandler(capturedBodyHandler)
            responseFuture.complete(response(status))

            result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.SUCCESS)
        }
    }

    @Test
    fun `retryable statuses and asynchronous IO failure are classified`() {
        listOf(408, 429, 500, 503, 599).forEach { status ->
            val responseFuture = CompletableFuture<HttpResponse<Void>>()
            val result = delivery(StubHttpClient(responseFuture)).deliver(event())
            responseFuture.complete(response(status))
            result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.RETRYABLE_FAILURE)
        }

        val responseFuture = CompletableFuture<HttpResponse<Void>>()
        val result = delivery(StubHttpClient(responseFuture)).deliver(event())
        responseFuture.completeExceptionally(java.io.IOException("socket-secret"))
        result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.RETRYABLE_FAILURE)
    }

    @Test
    fun `non IO asynchronous failure is terminal and encoder cancellation propagates`() {
        val responseFuture = CompletableFuture<HttpResponse<Void>>()
        val result = delivery(StubHttpClient(responseFuture)).deliver(event())
        responseFuture.completeExceptionally(IllegalArgumentException("request-secret"))
        result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.TERMINAL_FAILURE)

        val cancelledEncoder = LeaderAuditPayloadEncoder { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> {
            delivery(StubHttpClient(CompletableFuture()), encoder = cancelledEncoder).deliver(event())
        }
    }

    @Test
    fun `request uses POST timeout and immutable allow-listed headers`() {
        val responseFuture = CompletableFuture<HttpResponse<Void>>()
        val client = StubHttpClient(responseFuture)
        val result = delivery(
            client = client,
            headers = mapOf(
                "content-type" to "application/ignored",
                "Authorization" to "Bearer ${WEBHOOK_TOKEN_PLACEHOLDER}",
            ),
            encoder = LeaderAuditPayloadEncoder {
                LeaderAuditHttpPayload.of("application/audit+json", "audit".toByteArray())
            },
        ).deliver(event())

        val request = client.request.shouldNotBeNull()
        request.method().shouldBeEqualTo("POST")
        request.timeout().orElse(null).shouldNotBeNull().shouldBeEqualTo(Duration.ofSeconds(1))
        request.headers().firstValue("Content-Type").orElse(null)
            .shouldBeEqualTo("application/audit+json")
        request.headers().firstValue("Authorization").orElse(null)
            .shouldBeEqualTo("Bearer ${WEBHOOK_TOKEN_PLACEHOLDER}")
        request.bodyPublisher().orElse(null).shouldNotBeNull().contentLength().shouldBeEqualTo(5)

        responseFuture.complete(response(204))
        result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.SUCCESS)
    }

    @Test
    fun `other client errors are terminal and encoder failure is isolated`() {
        listOf(400, 401, 403, 404, 499).forEach { status ->
            val responseFuture = CompletableFuture<HttpResponse<Void>>()
            val result = delivery(StubHttpClient(responseFuture)).deliver(event())
            responseFuture.complete(response(status))
            result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.TERMINAL_FAILURE)
        }

        val client = StubHttpClient(CompletableFuture())
        val failedEncoder = LeaderAuditPayloadEncoder { throw IllegalStateException("encoder-secret") }
        val result = delivery(client, encoder = failedEncoder).deliver(event())
        result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.TERMINAL_FAILURE)
        client.request.shouldBeNull()
    }

    @Test
    fun `payload is immutable and configured lower bound is checked before request`() {
        val source = byteArrayOf(1, 2, 3)
        val payload = LeaderAuditHttpPayload.of("text/plain", source)
        source[0] = 9
        payload.body()[0].shouldBeEqualTo(1)
        val returned = payload.body()
        returned[1] = 8
        payload.body()[1].shouldBeEqualTo(2)

        val client = StubHttpClient(CompletableFuture())
        val result = delivery(
            client,
            httpOptions = LeaderAuditHttpOptions(2),
            encoder = LeaderAuditPayloadEncoder { payload },
        ).deliver(event())
        result.join().shouldBeEqualTo(LeaderAuditDeliveryResult.TERMINAL_FAILURE)
        client.request.shouldBeNull()
    }

    @Test
    fun `hard payload bound and unsafe endpoint, header, client contracts fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditHttpPayload.of("text/plain", ByteArray(1024 * 1024 + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditHttpPayload.of("text\nplain", byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditHttpOptions(0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditHttpOptions(1024 * 1024 + 1)
        }

        listOf(
            URI("http://audit.example.test/hook"),
            URI("https://user:password@audit.example.test/hook"),
            URI("https://audit.example.test/hook?token=secret"),
            URI("https://audit.example.test/hook#fragment"),
        ).forEach { unsafe ->
            assertFailsWith<IllegalArgumentException> {
                LeaderAuditTrustedHttpsEndpoint.trusted(unsafe)
            }
        }

        val endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(URI("https://audit.example.test/hook"))
        assertFailsWith<IllegalArgumentException> {
            delivery(StubHttpClient(CompletableFuture()), endpoint = endpoint, headers = mapOf("Host" to "evil"))
        }
        assertFailsWith<IllegalArgumentException> {
            delivery(StubHttpClient(CompletableFuture()), endpoint = endpoint, headers = mapOf("X-Api-Key" to "secret"))
        }
        assertFailsWith<IllegalArgumentException> {
            delivery(StubHttpClient(CompletableFuture()), endpoint = endpoint, headers = mapOf("Authorization" to "Bearer\nsecret"))
        }
        assertFailsWith<IllegalArgumentException> {
            delivery(StubHttpClient(CompletableFuture(), redirect = HttpClient.Redirect.ALWAYS), endpoint = endpoint)
        }
    }

    @Test
    fun `cancelled delivery future cancels the underlying HTTP request`() {
        val responseFuture = CompletableFuture<HttpResponse<Void>>()
        val client = StubHttpClient(responseFuture)
        val result = delivery(client).deliver(event())

        result.cancel(true).shouldBeTrue()
        responseFuture.isCancelled.shouldBeTrue()
        result.isCancelled.shouldBeTrue()
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertDiscardingBodyHandler(handler: BodyHandler<*>) {
        val subscriber = (handler as BodyHandler<Void>).apply(responseInfo())
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) = Unit

            override fun cancel() = Unit
        })
        subscriber.onNext(listOf(ByteBuffer.wrap("response-secret".toByteArray())))
        subscriber.onComplete()
        subscriber.getBody().toCompletableFuture().join().shouldBeNull()
    }

    private fun delivery(
        client: HttpClient,
        endpoint: LeaderAuditTrustedHttpsEndpoint = LeaderAuditTrustedHttpsEndpoint.trusted(
            URI("https://audit.example.test/hook"),
        ),
        headers: Map<String, String> = mapOf("Authorization" to "Bearer ${WEBHOOK_TOKEN_PLACEHOLDER}"),
        encoder: LeaderAuditPayloadEncoder = LeaderAuditPayloadEncoder {
            LeaderAuditHttpPayload.of("text/plain", "audit".toByteArray())
        },
        httpOptions: LeaderAuditHttpOptions = LeaderAuditHttpOptions.defaults(),
    ): HttpLeaderAuditDelivery = HttpLeaderAuditDelivery(
        client = client,
        endpoint = endpoint,
        headers = headers,
        encoder = encoder,
        exportOptions = exportOptions(),
        httpOptions = httpOptions,
    )

    private fun exportOptions(): LeaderAuditExportOptions = LeaderAuditExportOptions(
        queueCapacity = 8,
        maxInFlight = 2,
        maxAttempts = 2,
        attemptTimeout = Duration.ofSeconds(1),
        initialBackoff = Duration.ofMillis(1),
        maxBackoff = Duration.ofSeconds(1),
        executor = Executor { it.run() },
        scheduler = Executors.newSingleThreadScheduledExecutor().also(schedulers::add),
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

    private class StubHttpClient(
        private val responseFuture: CompletableFuture<HttpResponse<Void>>,
        private val redirect: HttpClient.Redirect = HttpClient.Redirect.NEVER,
    ) : HttpClient() {
        var request: HttpRequest? = null
        var bodyHandler: BodyHandler<*>? = null

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))

        override fun followRedirects(): Redirect = redirect

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = try {
            SSLContext.getDefault()
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }

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
            bodyHandler = responseBodyHandler
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

    private fun responseInfo(): HttpResponse.ResponseInfo = object : HttpResponse.ResponseInfo {
        override fun statusCode(): Int = 204

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    private companion object {
        const val WEBHOOK_TOKEN_PLACEHOLDER = "\${WEBHOOK_TOKEN}"
    }
}
