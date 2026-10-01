package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.lookup
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LeaderRouteRedirectSerializationTest {

    companion object: KLogging()

    @Test
    fun `public redirect context round trips with leader state`() {
        val evaluatedAt = Instant.parse("2026-08-23T03:00:00Z")
        val state = LeaderState.occupied(
            "orders-route",
            LeaderLease("node-a", evaluatedAt, evaluatedAt + 30.seconds()),
        )
        val context = LeaderRouteRedirectContext(
            LeaderSlot("orders-route", "node-b"),
            state,
            evaluatedAt,
            Duration.ofSeconds(5),
        )

        roundTrip(context) shouldBeEqualTo context
        LeaderRouteRedirectContext::class.lookup().serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `public request metadata round trips including unknown forwarded state`() {
        val metadata = LeaderRouteRedirectRequestMetadata(null, null)

        roundTrip(metadata) shouldBeEqualTo metadata
        LeaderRouteRedirectRequestMetadata::class.lookup().serialVersionUID shouldBeEqualTo 1L
    }

    private fun <T: Any> roundTrip(value: T): T {
        val bytes = BinarySerializers.FastFory.serialize(value)
        return BinarySerializers.FastFory.deserialize<T>(bytes).shouldNotBeNull()
    }
}
