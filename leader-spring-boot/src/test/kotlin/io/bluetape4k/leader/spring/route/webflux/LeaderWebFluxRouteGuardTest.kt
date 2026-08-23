package io.bluetape4k.leader.spring.route.webflux

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteRedirectProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteRejectionStatus
import io.bluetape4k.leader.spring.route.LeaderRouteAuthority
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.NullLeaderRouteAuthority
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadata
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadataProvider
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectResolver
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilterChain
import org.springframework.web.server.WebHandler
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LeaderWebFluxRouteGuardTest {

    private val slot = LeaderSlot("orders-route", "node-a")
    private val invocations = AtomicInteger()

    @BeforeEach
    fun resetInvocations() {
        invocations.set(0)
    }

    @Test
    fun `allowed request invokes handler exactly once off event loop`() {
        val evaluatedOnBlockingThread = AtomicBoolean(false)
        val authority = LeaderRouteAuthority {
            evaluatedOnBlockingThread.set(!Schedulers.isInNonBlockingThread())
            LeaderRouteDecision.Allowed
        }

        client(authority)
            .get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("served")

        invocations.get() shouldBeEqualTo 1
        evaluatedOnBlockingThread.get().shouldBeTrue()
    }

    @ParameterizedTest
    @EnumSource(LeaderRouteRejectionStatus::class)
    fun `rejected request returns configured status and empty sanitized response`(
        rejectionStatus: LeaderRouteRejectionStatus,
    ) {
        client(LeaderRouteAuthority { LeaderRouteDecision.NotLeader }, rejectionStatus)
            .get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isEqualTo(rejectionStatus.value)
            .expectHeader().doesNotExist("Location")
            .expectHeader().doesNotExist("X-Leader-Id")
            .expectBody().isEmpty

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `unavailable and authority failure reject before handler subscription`() {
        listOf(
            LeaderRouteAuthority { LeaderRouteDecision.Unavailable },
            LeaderRouteAuthority { error("backend password=secret") },
        ).forEach { authority ->
            client(authority)
                .get()
                .uri("/guarded")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().isEmpty
        }

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `resolver-only relative target returns temporary redirect without chain subscription`() {
        client(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(enabled = true),
            resolver = LeaderRouteRedirectResolver { java.net.URI("/leader/orders") },
        ).get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isTemporaryRedirect
            .expectHeader().valueEquals("Location", "/leader/orders")

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `resolver-only absolute target is rejected without location`() {
        client(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(enabled = true, allowedHosts = listOf("leader.example")),
            resolver = LeaderRouteRedirectResolver { java.net.URI("https://leader.example/orders") },
        ).get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().doesNotExist("Location")
            .expectBody().isEmpty
    }

    @Test
    fun `trusted raw metadata permits exact absolute target`() {
        val metadataProvider = LeaderRouteRedirectRequestMetadataProvider<org.springframework.web.server.ServerWebExchange> {
            LeaderRouteRedirectRequestMetadata(true, "10.0.0.10")
        }
        client(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(
                enabled = true,
                allowedHosts = listOf("leader.example"),
                trustedProxyAddresses = listOf("10.0.0.10"),
            ),
            resolver = LeaderRouteRedirectResolver { java.net.URI("https://leader.example/orders") },
            metadataProvider = metadataProvider,
        ).get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isTemporaryRedirect
            .expectHeader().valueEquals("Location", "https://leader.example/orders")
    }

    @Test
    fun `redirect disabled never calls resolver or metadata provider`() {
        val resolverCalls = AtomicInteger()
        val providerCalls = AtomicInteger()
        client(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            resolver = LeaderRouteRedirectResolver {
                resolverCalls.incrementAndGet()
                java.net.URI("/leader/orders")
            },
            metadataProvider = LeaderRouteRedirectRequestMetadataProvider {
                providerCalls.incrementAndGet()
                LeaderRouteRedirectRequestMetadata(false, null)
            },
        ).get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isEqualTo(503)

        resolverCalls.get() shouldBeEqualTo 0
        providerCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `null Java authority result fails closed with configured status`() {
        client(NullLeaderRouteAuthority(), LeaderRouteRejectionStatus.LOCKED)
            .get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isEqualTo(423)
            .expectBody().isEmpty

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `cancellation during authority evaluation never subscribes handler`() {
        val authorityStarted = CountDownLatch(1)
        val authorityRelease = CountDownLatch(1)
        val authorityFinished = CountDownLatch(1)
        val authority = LeaderRouteAuthority {
            authorityStarted.countDown()
            try {
                authorityRelease.await(2, TimeUnit.SECONDS)
                LeaderRouteDecision.Allowed
            } finally {
                authorityFinished.countDown()
            }
        }
        val factory = factory(authority)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/guarded").build())
        val chain = WebFilterChain {
            invocations.incrementAndGet()
            Mono.empty()
        }

        val subscription = factory.filter(slot).filter(exchange, chain).subscribe()
        authorityStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
        subscription.dispose()
        authorityRelease.countDown()
        authorityFinished.await(2, TimeUnit.SECONDS).shouldBeTrue()

        invocations.get() shouldBeEqualTo 0
        subscription.isDisposed.shouldBeTrue()
    }

    @Test
    fun `cancellation before queued evaluation never invokes authority or handler`() {
        val evaluated = AtomicBoolean(false)
        val scheduledEvaluation = AtomicReference<Runnable>()
        val evaluationScheduler = Schedulers.fromExecutor { task -> scheduledEvaluation.set(task) }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/guarded").build())
        val chain = WebFilterChain {
            invocations.incrementAndGet()
            Mono.empty()
        }

        val subscriber = factory(
            authority = LeaderRouteAuthority {
                evaluated.set(true)
                LeaderRouteDecision.Allowed
            },
            evaluationScheduler = evaluationScheduler,
        ).filter(slot).filter(exchange, chain).subscribe()

        subscriber.dispose()
        scheduledEvaluation.get().run()

        subscriber.isDisposed.shouldBeTrue()
        evaluated.get() shouldBeEqualTo false
        invocations.get() shouldBeEqualTo 0
        evaluationScheduler.dispose()
    }

    @Test
    fun `cancellation failure from authority is preserved`() {
        val failure = AtomicReference<Throwable>()
        val completed = CountDownLatch(1)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/guarded").build())
        val chain = WebFilterChain {
            invocations.incrementAndGet()
            Mono.empty()
        }

        factory(LeaderRouteAuthority { throw CancellationException("cancelled") })
            .filter(slot)
            .filter(exchange, chain)
            .subscribe({}, {
                failure.set(it)
                completed.countDown()
            })

        completed.await(2, TimeUnit.SECONDS).shouldBeTrue()
        failure.get().shouldBeInstanceOf<CancellationException>()
        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `rejected request never starts coroutine handler`() {
        val coroutineStarted = AtomicBoolean(false)
        val handler = WebHandler {
            mono {
                coroutineStarted.set(true)
            }.then()
        }
        val client = WebTestClient.bindToWebHandler(handler)
            .webFilter(factory(LeaderRouteAuthority { LeaderRouteDecision.NotLeader }).filter(slot))
            .build()

        client.get()
            .uri("/guarded")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody().isEmpty

        coroutineStarted.get() shouldBeEqualTo false
    }

    @Test
    fun `cancelling allowed request propagates to coroutine handler`() {
        val coroutineStarted = CountDownLatch(1)
        val coroutineCancelled = CountDownLatch(1)
        val handler = WebHandler {
            mono {
                coroutineStarted.countDown()
                try {
                    awaitCancellation()
                } finally {
                    coroutineCancelled.countDown()
                }
            }.then()
        }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/guarded").build())
        val chain = WebFilterChain { handler.handle(it) }

        val subscription = factory(LeaderRouteAuthority { LeaderRouteDecision.Allowed })
            .filter(slot)
            .filter(exchange, chain)
            .subscribe()

        coroutineStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
        subscription.dispose()
        coroutineCancelled.await(2, TimeUnit.SECONDS).shouldBeTrue()
        subscription.isDisposed.shouldBeTrue()
    }

    private fun client(
        authority: LeaderRouteAuthority,
        rejectionStatus: LeaderRouteRejectionStatus = LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE,
        redirect: LeaderRouteRedirectProperties = LeaderRouteRedirectProperties(),
        resolver: LeaderRouteRedirectResolver? = null,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<org.springframework.web.server.ServerWebExchange>? = null,
    ): WebTestClient {
        val handler = WebHandler { exchange ->
            invocations.incrementAndGet()
            exchange.response.headers.contentType = MediaType.TEXT_PLAIN
            val bytes = "served".toByteArray(StandardCharsets.UTF_8)
            exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
        }
        val routeFilter = when {
            resolver != null && metadataProvider != null -> factory(authority, rejectionStatus, redirect)
                .filter(slot, resolver, metadataProvider)
            resolver != null -> factory(authority, rejectionStatus, redirect).filter(slot, resolver)
            else -> factory(authority, rejectionStatus, redirect).filter(slot)
        }
        return WebTestClient.bindToWebHandler(handler)
            .webFilter(routeFilter)
            .build()
    }

    private fun factory(
        authority: LeaderRouteAuthority,
        rejectionStatus: LeaderRouteRejectionStatus = LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE,
        redirect: LeaderRouteRedirectProperties = LeaderRouteRedirectProperties(),
        evaluationScheduler: Scheduler = Schedulers.boundedElastic(),
    ): LeaderWebFluxRouteGuardFactory =
        LeaderWebFluxRouteGuardFactory(
            runtime = LeaderRouteAuthorityRuntime(authority),
            properties = LeaderRouteGuardProperties(rejectionStatus = rejectionStatus, redirect = redirect),
            evaluationScheduler = evaluationScheduler,
        )
}
