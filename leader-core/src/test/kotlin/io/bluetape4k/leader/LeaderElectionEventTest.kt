package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionEventTest {

    @Test
    fun `Elected - default construction leaves leaderId and leaseExpiry null`() {
        val event = LeaderElectionEvent.Elected("my-lock")

        event.lockName shouldBeEqualTo "my-lock"
        event.leaderId.shouldBeNull()
        event.leaseExpiry.shouldBeNull()
        event.leader.shouldBeNull()
    }

    @Test
    fun `Elected - explicit leaderId is preserved`() {
        val event = LeaderElectionEvent.Elected("my-lock", leaderId = "node-1")

        event.lockName shouldBeEqualTo "my-lock"
        event.leaderId shouldBeEqualTo "node-1"
        event.leaseExpiry.shouldBeNull()
        event.leader.shouldBeNull()
    }

    @Test
    fun `Elected - explicit leaderId and leaseExpiry are both preserved`() {
        val expiry = Instant.parse("2025-06-01T00:00:00Z")
        val event = LeaderElectionEvent.Elected("my-lock", leaderId = "node-1", leaseExpiry = expiry)

        event.lockName shouldBeEqualTo "my-lock"
        event.leaderId shouldBeEqualTo "node-1"
        event.leaseExpiry shouldBeEqualTo expiry
        event.leader.shouldBeNull()
    }

    @Test
    fun `Elected - fromLease copies lease metadata`() {
        val electedAt = Instant.parse("2025-06-01T00:00:00Z")
        val expiry = electedAt.plusSeconds(30)
        val lease = LeaderLease(
            auditLeaderId = "node-1",
            electedAt = electedAt,
            leaseUntil = expiry,
            nodeId = "host-a",
        )

        val event = LeaderElectionEvent.Elected.fromLease("my-lock", lease)

        event.lockName shouldBeEqualTo "my-lock"
        event.leaderId shouldBeEqualTo "node-1"
        event.leaseExpiry shouldBeEqualTo expiry
        event.leader.shouldNotBeNull() shouldBeEqualTo lease
    }

    @Test
    fun `Elected - serialization round-trip preserves all fields`() {
        val expiry = Instant.parse("2025-06-01T00:00:00Z")
        val original = LeaderElectionEvent.Elected("my-lock", leaderId = "node-1", leaseExpiry = expiry)

        val bytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos -> oos.writeObject(original) }
            baos.toByteArray()
        }
        val deserialized = ByteArrayInputStream(bytes).use { bais ->
            ObjectInputStream(bais).use { ois -> ois.readObject() as LeaderElectionEvent.Elected }
        }

        deserialized shouldBeEqualTo original
        deserialized.lockName shouldBeEqualTo "my-lock"
        deserialized.leaderId shouldBeEqualTo "node-1"
        deserialized.leaseExpiry shouldBeEqualTo expiry
        deserialized.leader.shouldBeNull()
    }

    @Test
    fun `Elected - serialization round-trip with null fields`() {
        val original = LeaderElectionEvent.Elected("lock-x")

        val bytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos -> oos.writeObject(original) }
            baos.toByteArray()
        }
        val deserialized = ByteArrayInputStream(bytes).use { bais ->
            ObjectInputStream(bais).use { ois -> ois.readObject() as LeaderElectionEvent.Elected }
        }

        deserialized shouldBeEqualTo original
        deserialized.leaderId.shouldBeNull()
        deserialized.leaseExpiry.shouldBeNull()
        deserialized.leader.shouldBeNull()
    }

    @Test
    fun `Revoked - serialization round-trip preserves lock name`() {
        val original = LeaderElectionEvent.Revoked("lock-x")

        val bytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos -> oos.writeObject(original) }
            baos.toByteArray()
        }
        val deserialized = ByteArrayInputStream(bytes).use { bais ->
            ObjectInputStream(bais).use { ois -> ois.readObject() as LeaderElectionEvent.Revoked }
        }

        deserialized shouldBeEqualTo original
        deserialized.lockName shouldBeEqualTo "lock-x"
    }

    @Test
    fun `Skipped - serialization round-trip preserves lock name`() {
        val original = LeaderElectionEvent.Skipped("lock-x")

        val bytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos -> oos.writeObject(original) }
            baos.toByteArray()
        }
        val deserialized = ByteArrayInputStream(bytes).use { bais ->
            ObjectInputStream(bais).use { ois -> ois.readObject() as LeaderElectionEvent.Skipped }
        }

        deserialized shouldBeEqualTo original
        deserialized.lockName shouldBeEqualTo "lock-x"
    }

    @Test
    fun `Elected - data class equality considers lease metadata`() {
        val expiry = Instant.parse("2025-06-01T00:00:00Z")
        val lease = LeaderLease("node-1", leaseUntil = expiry)

        val a = LeaderElectionEvent.Elected.fromLease("lock", lease)
        val b = LeaderElectionEvent.Elected.fromLease("lock", lease)
        val c = LeaderElectionEvent.Elected("lock", leaderId = "node-1", leaseExpiry = expiry)
        val d = LeaderElectionEvent.Elected("lock")

        a shouldBeEqualTo b
        (a == c) shouldBeEqualTo false
        (a == d) shouldBeEqualTo false
    }
}
