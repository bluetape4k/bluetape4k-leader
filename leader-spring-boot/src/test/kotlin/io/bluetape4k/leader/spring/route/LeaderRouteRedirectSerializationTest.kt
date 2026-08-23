package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.time.Duration
import java.time.Instant

class LeaderRouteRedirectSerializationTest {

    @Test
    fun `public redirect context round trips with leader state`() {
        val evaluatedAt = Instant.parse("2026-08-23T03:00:00Z")
        val state = LeaderState.occupied(
            "orders-route",
            LeaderLease("node-a", evaluatedAt, evaluatedAt.plusSeconds(30)),
        )
        val context = LeaderRouteRedirectContext(
            LeaderSlot("orders-route", "node-b"),
            state,
            evaluatedAt,
            Duration.ofSeconds(5),
        )

        roundTrip(context) shouldBeEqualTo context
        ObjectStreamClass.lookup(LeaderRouteRedirectContext::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `public request metadata round trips including unknown forwarded state`() {
        val metadata = LeaderRouteRedirectRequestMetadata(null, null)

        roundTrip(metadata) shouldBeEqualTo metadata
        ObjectStreamClass.lookup(LeaderRouteRedirectRequestMetadata::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            @Suppress("UNCHECKED_CAST")
            it.readObject() as T
        }
    }
}
