package io.bluetape4k.leader.identity

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldNotBe
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderSlotTest {

    companion object: KLogging()

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

        a shouldNotBe b
        a shouldBeEqualTo b
        a.hashCode() shouldBeEqualTo b.hashCode()
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
        log.debug { "a=$a, b=$b" }
        a shouldNotBeEqualTo b
    }

    @Test
    fun `RandomLeaderIdProvider - custom length`() {
        val provider = RandomLeaderIdProvider(length = 6)
        val id = provider.nextLeaderId("lock")
        id.shouldNotBeBlank()
        id.length shouldBeLessOrEqualTo 8 // Base58 chars, length is approximate
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
        id shouldStartWith "tenant-acme:"
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
        id shouldStartWith "env-prod#"
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
        log.debug { "result=$result" }
        result.shouldNotBeBlank()
    }

    @Test
    @OptIn(LeaderInternalApi::class)
    fun `safeNextLeaderId - provider throws - falls back to default`() {
        val provider = LeaderIdProvider { _ -> throw RuntimeException("boom") }
        val result = safeNextLeaderId(provider, "lock")
        log.debug { "result=$result" }
        result.shouldNotBeBlank()
    }
}
