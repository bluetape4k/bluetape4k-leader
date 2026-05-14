package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
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
    }

    @Test
    fun `Elected - explicit leaderId is preserved`() {
        val event = LeaderElectionEvent.Elected("my-lock", leaderId = "node-1")

        event.lockName shouldBeEqualTo "my-lock"
        event.leaderId shouldBeEqualTo "node-1"
        event.leaseExpiry.shouldBeNull()
    }

    @Test
    fun `Elected - explicit leaderId and leaseExpiry are both preserved`() {
        val expiry = Instant.parse("2025-06-01T00:00:00Z")
        val event = LeaderElectionEvent.Elected("my-lock", leaderId = "node-1", leaseExpiry = expiry)

        event.lockName shouldBeEqualTo "my-lock"
        event.leaderId shouldBeEqualTo "node-1"
        event.leaseExpiry shouldBeEqualTo expiry
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
    }

    @Test
    fun `Elected - data class equality considers leaderId and leaseExpiry`() {
        val expiry = Instant.parse("2025-06-01T00:00:00Z")

        val a = LeaderElectionEvent.Elected("lock", leaderId = "node-1", leaseExpiry = expiry)
        val b = LeaderElectionEvent.Elected("lock", leaderId = "node-1", leaseExpiry = expiry)
        val c = LeaderElectionEvent.Elected("lock", leaderId = "node-2", leaseExpiry = expiry)
        val d = LeaderElectionEvent.Elected("lock")

        a shouldBeEqualTo b
        (a == c) shouldBeEqualTo false
        (a == d) shouldBeEqualTo false
    }
}
