package io.bluetape4k.leader.identity

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderSlotTest {

    companion object : KLogging()

    // --- LeaderSlot ---

    @Test
    fun `LeaderSlot - basic construction`() {
        val slot = LeaderSlot("my-lock", "node-a")
        slot.lockName shouldBeEqualTo "my-lock"
        slot.leaderId shouldBeEqualTo "node-a"
    }

    @Test
    fun `LeaderSlot - blank lockName throws`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderSlot("", "node-a")
        }
    }

    @Test
    fun `LeaderSlot - blank leaderId throws`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderSlot("my-lock", "")
        }
    }

    @Test
    fun `LeaderSlot - of factory uses provider`() {
        val slot = LeaderSlot.of("billing-lock", RandomLeaderIdProvider.Default)
        slot.lockName shouldBeEqualTo "billing-lock"
        slot.leaderId.shouldNotBeBlank()
    }

    @Test
    fun `LeaderSlot - equals and hashCode`() {
        val a = LeaderSlot("lock", "node-a")
        val b = LeaderSlot("lock", "node-a")
        a shouldBeEqualTo b
        (a.hashCode() == b.hashCode()) shouldBeEqualTo true
    }

    @Test
    fun `LeaderSlot - copy`() {
        val orig = LeaderSlot("lock", "node-a")
        val copy = orig.copy(leaderId = "node-b")
        copy.leaderId shouldBeEqualTo "node-b"
        copy.lockName shouldBeEqualTo "lock"
    }

    // --- RandomLeaderIdProvider ---

    @Test
    fun `RandomLeaderIdProvider - Default produces non-blank id`() {
        val id = RandomLeaderIdProvider.Default.nextLeaderId("any-lock")
        id.shouldNotBeBlank()
    }

    @Test
    fun `RandomLeaderIdProvider - different calls produce different ids`() {
        val a = RandomLeaderIdProvider.Default.nextLeaderId("lock")
        val b = RandomLeaderIdProvider.Default.nextLeaderId("lock")
        (a == b) shouldBeEqualTo false
    }

    @Test
    fun `RandomLeaderIdProvider - custom length`() {
        val provider = RandomLeaderIdProvider(length = 6)
        val id = provider.nextLeaderId("lock")
        id.shouldNotBeBlank()
        (id.length <= 8) shouldBeEqualTo true // Base58 chars, length is approximate
    }

    // --- CompositeLeaderIdProvider ---

    @Test
    fun `CompositeLeaderIdProvider - prefixes output`() {
        val provider = CompositeLeaderIdProvider(
            prefix = "tenant-acme",
            separator = ":",
            delegate = RandomLeaderIdProvider.Default,
        )
        val id = provider.nextLeaderId("lock")
        (id.startsWith("tenant-acme:")) shouldBeEqualTo true
    }

    @Test
    fun `CompositeLeaderIdProvider - blank prefix throws`() {
        assertFailsWith<IllegalArgumentException> {
            CompositeLeaderIdProvider(prefix = "")
        }
    }

    @Test
    fun `CompositeLeaderIdProvider - custom separator`() {
        val provider = CompositeLeaderIdProvider(prefix = "env-prod", separator = "#")
        val id = provider.nextLeaderId("lock")
        (id.startsWith("env-prod#")) shouldBeEqualTo true
    }

    // --- safeNextLeaderId ---

    @Test
    @OptIn(LeaderInternalApi::class)
    fun `safeNextLeaderId - normal provider - returns provider result`() {
        val provider = LeaderIdProvider { _ -> "fixed-id" }
        val result = safeNextLeaderId(provider, "lock")
        result shouldBeEqualTo "fixed-id"
    }

    @Test
    @OptIn(LeaderInternalApi::class)
    fun `safeNextLeaderId - provider returns blank - falls back to default`() {
        val provider = LeaderIdProvider { _ -> "" }
        val result = safeNextLeaderId(provider, "lock")
        result.shouldNotBeBlank()
    }

    @Test
    @OptIn(LeaderInternalApi::class)
    fun `safeNextLeaderId - provider throws - falls back to default`() {
        val provider = LeaderIdProvider { _ -> throw RuntimeException("boom") }
        val result = safeNextLeaderId(provider, "lock")
        result.shouldNotBeBlank()
    }
}
