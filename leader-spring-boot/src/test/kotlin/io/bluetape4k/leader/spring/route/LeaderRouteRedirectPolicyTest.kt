package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.spring.properties.LeaderRouteRedirectProperties
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class LeaderRouteRedirectPolicyTest {

    private val now = Instant.parse("2026-08-23T00:00:00Z")
    private val clock = Clock.fixed(now, java.time.ZoneOffset.UTC)
    private val slot = LeaderSlot("orders-route", "node-a")
    private val evaluation = LeaderRouteEvaluation(
        decision = LeaderRouteDecision.NotLeader,
        leaderState = LeaderState.occupied(
            "orders-route",
            LeaderLease("node-b", leaseUntil = now.plusSeconds(30)),
        ),
        evaluatedAt = now,
    )

    @Test
    fun `relative resolver target redirects without request metadata`() {
        val policy = policy()

        val target = policy.redirect(
            slot,
            evaluation,
            LeaderRouteRedirectResolver { URI("/leader/orders") },
            metadata = null,
            framework = LeaderRouteRedirectFramework.MVC,
        )

        target shouldBeEqualTo URI("/leader/orders")
    }

    @Test
    fun `absolute resolver target is rejected when metadata is unknown`() {
        val policy = policy()

        val target = policy.redirect(
            slot,
            evaluation,
            LeaderRouteRedirectResolver { URI("https://leader.example/orders") },
            metadata = null,
            framework = LeaderRouteRedirectFramework.MVC,
        )

        target.shouldBeNull()
    }

    @Test
    fun `forwarded absolute target requires exact trusted transport peer`() {
        val policy = policy(trustedProxyAddresses = listOf("10.0.0.10"))
        val resolver = LeaderRouteRedirectResolver { URI("https://leader.example/orders") }

        policy.redirect(
            slot,
            evaluation,
            resolver,
            LeaderRouteRedirectRequestMetadata(true, "10.0.0.11"),
            LeaderRouteRedirectFramework.WEBFLUX,
        ).shouldBeNull()

        policy.redirect(
            slot,
            evaluation,
            resolver,
            LeaderRouteRedirectRequestMetadata(true, "10.0.0.10"),
            LeaderRouteRedirectFramework.WEBFLUX,
        ) shouldBeEqualTo URI("https://leader.example/orders")
    }

    @Test
    fun `non numeric transport peer never resolves as a trusted address`() {
        val policy = policy(trustedProxyAddresses = listOf("127.0.0.1"))
        val resolver = LeaderRouteRedirectResolver { URI("https://leader.example/orders") }

        policy.redirect(
            slot,
            evaluation,
            resolver,
            LeaderRouteRedirectRequestMetadata(true, "localhost"),
            LeaderRouteRedirectFramework.MVC,
        ).shouldBeNull()

        policy.redirect(
            slot,
            evaluation,
            resolver,
            LeaderRouteRedirectRequestMetadata(true, "010.0.0.1"),
            LeaderRouteRedirectFramework.MVC,
        ).shouldBeNull()
    }

    @Test
    fun `stale state rejects resolver without invoking it`() {
        var calls = 0
        val staleEvaluation = evaluation.copy(
            leaderState = LeaderState.occupied(
                "orders-route",
                LeaderLease("node-b", leaseUntil = now.plusSeconds(5)),
            ),
        )
        val policy = policy(safetyWindow = Duration.ofSeconds(5))

        policy.redirect(
            slot,
            staleEvaluation,
            LeaderRouteRedirectResolver {
                calls++
                URI("/leader/orders")
            },
            metadata = null,
            framework = LeaderRouteRedirectFramework.MVC,
        ).shouldBeNull()

        calls shouldBeEqualTo 0
    }

    @Test
    fun `resolver receives one immutable evaluation timestamp and safety window`() {
        var captured: LeaderRouteRedirectContext? = null
        val policy = policy(safetyWindow = Duration.ofSeconds(7))

        policy.redirect(
            slot,
            evaluation,
            LeaderRouteRedirectResolver {
                captured = it
                URI("/leader/orders")
            },
            metadata = null,
            framework = LeaderRouteRedirectFramework.MVC,
        ) shouldBeEqualTo URI("/leader/orders")

        captured?.slot shouldBeEqualTo slot
        captured?.evaluatedAt shouldBeEqualTo now
        captured?.leaseSafetyWindow shouldBeEqualTo Duration.ofSeconds(7)
        captured?.leaderState shouldBeEqualTo evaluation.leaderState
    }

    @Test
    fun `failure observer receives only fixed reason and framework`() {
        val observations = CopyOnWriteArrayList<Pair<LeaderRouteRedirectFailureReason, LeaderRouteRedirectFramework>>()
        val policy = LeaderRouteRedirectPolicy(
            LeaderRouteRedirectProperties(enabled = true),
        ) { reason, framework -> observations += reason to framework }

        policy.redirect(
            slot,
            evaluation,
            LeaderRouteRedirectResolver { URI("https://leader.example/orders") },
            metadata = null,
            framework = LeaderRouteRedirectFramework.WEBFLUX,
        ).shouldBeNull()

        observations shouldBeEqualTo listOf(
            LeaderRouteRedirectFailureReason.METADATA_UNKNOWN to LeaderRouteRedirectFramework.WEBFLUX,
        )
    }

    @Test
    fun `ordinary resolver exception fails closed with fixed callback reason`() {
        val observations = CopyOnWriteArrayList<Pair<LeaderRouteRedirectFailureReason, LeaderRouteRedirectFramework>>()
        val policy = LeaderRouteRedirectPolicy(
            LeaderRouteRedirectProperties(enabled = true),
        ) { reason, framework -> observations += reason to framework }

        policy.redirect(
            slot,
            evaluation,
            LeaderRouteRedirectResolver { throw Exception("sensitive resolver detail") },
            metadata = null,
            framework = LeaderRouteRedirectFramework.MVC,
        ).shouldBeNull()

        observations shouldBeEqualTo listOf(
            LeaderRouteRedirectFailureReason.CALLBACK_FAILURE to LeaderRouteRedirectFramework.MVC,
        )
    }

    private fun policy(
        trustedProxyAddresses: List<String> = emptyList(),
        safetyWindow: Duration = Duration.ZERO,
    ) = LeaderRouteRedirectPolicy(
        LeaderRouteRedirectProperties(
            enabled = true,
            allowedHosts = listOf("leader.example"),
            trustedProxyAddresses = trustedProxyAddresses,
            leaseSafetyWindow = safetyWindow,
        ),
    )
}
