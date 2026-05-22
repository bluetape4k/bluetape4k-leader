package io.bluetape4k.leader.consul.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ConsulOwnerPayloadTest {

    @Test
    fun `round trips owner payload json`() {
        val payload = ConsulOwnerPayload(
            ownerToken = "token-1",
            auditLeaderId = "audit-node",
            nodeId = "node-a",
            electedAt = Instant.parse("2026-05-22T01:02:03Z"),
            leaseUntil = Instant.parse("2026-05-22T01:02:13Z"),
        )

        val decoded = ConsulOwnerPayload.fromJson(payload.toJson())

        decoded shouldBeEqualTo payload
        decoded?.toLeaderLease()?.auditLeaderId shouldBeEqualTo "audit-node"
        decoded?.toLeaderLease()?.nodeId shouldBeEqualTo "node-a"
    }

    @Test
    fun `escapes string fields`() {
        val payload = ConsulOwnerPayload(
            ownerToken = "token\"\\\n",
            auditLeaderId = "audit\tid",
            nodeId = "node\rid",
            electedAt = Instant.parse("2026-05-22T01:02:03Z"),
            leaseUntil = Instant.parse("2026-05-22T01:02:13Z"),
        )

        ConsulOwnerPayload.fromJson(payload.toJson()) shouldBeEqualTo payload
    }

    @Test
    fun `returns null for malformed payload`() {
        ConsulOwnerPayload.fromJson("[]").shouldBeNull()
        ConsulOwnerPayload.fromJson("""{"ownerToken":"missing-required-fields"}""").shouldBeNull()
    }
}
