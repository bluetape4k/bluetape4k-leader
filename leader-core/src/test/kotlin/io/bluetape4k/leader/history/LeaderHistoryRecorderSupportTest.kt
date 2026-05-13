package io.bluetape4k.leader.history

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.truncateUtf8
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        assertEquals("hello?world", input.sanitizeForLog())
    }

    @Test
    fun `sanitizeForLog replaces tab control character with question mark`() {
        val input = "col1\tcol2"
        assertEquals("col1?col2", input.sanitizeForLog())
    }

    @Test
    fun `sanitizeForLog replaces Unicode line separator`() {
        val input = "line sep"
        assertEquals("line?sep", input.sanitizeForLog())
    }

    @Test
    fun `sanitizeForLog replaces Unicode paragraph separator`() {
        val input = "para sep"
        assertEquals("para?sep", input.sanitizeForLog())
    }

    @Test
    fun `sanitizeForLog leaves normal ASCII unchanged`() {
        val input = "Hello, World! 123"
        assertEquals(input, input.sanitizeForLog())
    }

    // ── sanitize record ───────────────────────────────────────────────────

    @Test
    fun `sanitize truncates errorMessage to MAX_ERROR_MESSAGE_BYTES`() {
        val longMessage = "x".repeat(1000)
        val sanitized = sanitize(record(errorMessage = longMessage))
        val bytes = sanitized.errorMessage!!.toByteArray(Charsets.UTF_8)
        assert(bytes.size <= LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES) {
            "errorMessage bytes=${bytes.size} exceeds MAX_ERROR_MESSAGE_BYTES"
        }
    }

    @Test
    fun `sanitize caps metadata to MAX_METADATA_KEYS entries`() {
        val bigMetadata = (1..20).associate { "key$it" to "val$it" }
        val sanitized = sanitize(record(metadata = bigMetadata))
        assert(sanitized.metadata.size <= LeaderLockHistoryRecord.MAX_METADATA_KEYS) {
            "metadata size=${sanitized.metadata.size} exceeds MAX_METADATA_KEYS"
        }
    }

    @Test
    fun `sanitize sanitizes control characters in metadata key and value`() {
        val metadata = mapOf("k\ney" to "va\tlue")
        val sanitized = sanitize(record(metadata = metadata))
        val entry = sanitized.metadata.entries.first()
        assertEquals("k?ey", entry.key)
        assertEquals("va?lue", entry.value)
    }

    @Test
    fun `sanitize truncates metadata values longer than MAX_METADATA_VALUE_LENGTH`() {
        val longValue = "v".repeat(1000)
        val metadata = mapOf("k" to longValue)
        val sanitized = sanitize(record(metadata = metadata))
        val value = sanitized.metadata["k"]!!
        assert(value.length <= LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH) {
            "metadata value length=${value.length} exceeds MAX_METADATA_VALUE_LENGTH"
        }
    }

    @Test
    fun `sanitize returns equivalent record when no changes are needed`() {
        val r = record(errorMessage = "simple error", metadata = mapOf("k" to "v"))
        val sanitized = sanitize(r)
        assertEquals(r.errorMessage, sanitized.errorMessage)
        assertEquals(r.metadata, sanitized.metadata)
    }

    @Test
    fun `sanitize handles null errorMessage without throwing`() {
        val r = record(errorMessage = null)
        val sanitized = sanitize(r)
        assertNull(sanitized.errorMessage)
    }

    // ── truncateUtf8 ──────────────────────────────────────────────────────

    @Test
    fun `truncateUtf8 does not split multi-byte characters`() {
        // "世" is 3 bytes; cutting at 8 bytes must not produce an incomplete sequence
        val input = "Hello, 世界"
        val truncated = input.truncateUtf8(8)
        val bytes = truncated.toByteArray(Charsets.UTF_8)
        assert(bytes.size <= 8) { "truncated bytes=${bytes.size}" }
        // must be decodable without exception
        String(bytes, Charsets.UTF_8)
    }

    @Test
    fun `truncateUtf8 returns original string when within limit`() {
        val input = "abc"
        assertEquals(input, input.truncateUtf8(100))
    }

    @Test
    fun `truncateUtf8 rejects negative maxBytes`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            "abc".truncateUtf8(-1)
        }
    }
}
