package io.bluetape4k.leader.history

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.truncateUtf8
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant

class LeaderHistoryRecorderSupportTest {

    companion object : KLogging()

    private val now = Instant.parse("2026-05-14T10:00:00Z")

    private fun record(
        errorMessage: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = LeaderLockHistoryRecord(
        lockName = "lock",
        token = "tok",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = now,
        lockedUntil = now.plusSeconds(60),
        errorMessage = errorMessage,
        metadata = metadata,
    )

    // ── sanitizeForLog ────────────────────────────────────────────────────

    @Test
    fun `sanitizeForLog replaces newline control character with question mark`() {
        val input = "hello\nworld"
        input.sanitizeForLog() shouldBeEqualTo "hello?world"
    }

    @Test
    fun `sanitizeForLog replaces tab control character with question mark`() {
        val input = "col1\tcol2"
        input.sanitizeForLog() shouldBeEqualTo "col1?col2"
    }

    @Test
    fun `sanitizeForLog replaces C1 control character with question mark`() {
        val input = "left\u0085right"
        input.sanitizeForLog() shouldBeEqualTo "left?right"
    }

    @Test
    fun `sanitizeForLog replaces Unicode line separator`() {
        val input = "line sep"
        input.sanitizeForLog() shouldBeEqualTo "line?sep"
    }

    @Test
    fun `sanitizeForLog replaces Unicode paragraph separator`() {
        val input = "para sep"
        input.sanitizeForLog() shouldBeEqualTo "para?sep"
    }

    @Test
    fun `sanitizeForLog leaves normal ASCII unchanged`() {
        val input = "Hello, World! 123"
        input.sanitizeForLog() shouldBeEqualTo input
        (input.sanitizeForLog() === input) shouldBe true
    }

    // ── sanitize record ───────────────────────────────────────────────────

    @Test
    fun `sanitize truncates errorMessage to MAX_ERROR_MESSAGE_BYTES`() {
        val longMessage = "x".repeat(1000)
        val sanitized = sanitize(record(errorMessage = longMessage))
        val errorMessage = requireNotNull(sanitized.errorMessage)
        val bytes = errorMessage.toByteArray(Charsets.UTF_8)
        bytes.size shouldBeLessOrEqualTo LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES
    }

    @Test
    fun `sanitize caps metadata to MAX_METADATA_KEYS entries`() {
        val bigMetadata = (1..20).associate { "key$it" to "val$it" }
        val sanitized = sanitize(record(metadata = bigMetadata))
        sanitized.metadata.size shouldBeLessOrEqualTo LeaderLockHistoryRecord.MAX_METADATA_KEYS
    }

    @Test
    fun `sanitize sanitizes control characters in metadata key and value`() {
        val metadata = mapOf("k\ney" to "va\tlue")
        val sanitized = sanitize(record(metadata = metadata))
        val entry = sanitized.metadata.entries.first()
        entry.key shouldBeEqualTo "k?ey"
        entry.value shouldBeEqualTo "va?lue"
    }

    @Test
    fun `sanitize truncates metadata values longer than MAX_METADATA_VALUE_LENGTH`() {
        val longValue = "v".repeat(1000)
        val metadata = mapOf("k" to longValue)
        val sanitized = sanitize(record(metadata = metadata))
        val value = requireNotNull(sanitized.metadata["k"])
        value.length shouldBeLessOrEqualTo LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH
    }

    @Test
    fun `sanitize returns equivalent record when no changes are needed`() {
        val r = record(errorMessage = "simple error", metadata = mapOf("k" to "v"))
        val sanitized = sanitize(r)
        (sanitized === r) shouldBe true
        sanitized.errorMessage shouldBeEqualTo r.errorMessage
        sanitized.metadata shouldBeEqualTo r.metadata
    }

    @Test
    fun `sanitize handles null errorMessage without throwing`() {
        val r = record(errorMessage = null)
        val sanitized = sanitize(r)
        sanitized.errorMessage.shouldBeNull()
    }

    // ── truncateUtf8 ──────────────────────────────────────────────────────

    @Test
    fun `truncateUtf8 does not split multi-byte characters`() {
        // "世" is 3 bytes; cutting at 8 bytes must not produce an incomplete sequence
        val input = "Hello, 世界"
        val truncated = input.truncateUtf8(8)
        val bytes = truncated.toByteArray(Charsets.UTF_8)
        bytes.size shouldBeLessOrEqualTo 8
        // must be decodable without exception
        String(bytes, Charsets.UTF_8)
    }

    @Test
    fun `truncateUtf8 returns original string when within limit`() {
        val input = "abc"
        input.truncateUtf8(100) shouldBeEqualTo input
    }

    @Test
    fun `truncateUtf8 rejects negative maxBytes`() {
        assertFailsWith<IllegalArgumentException> {
            "abc".truncateUtf8(-1)
        }
    }
}
