package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

class LeaderAuditExportEventTest {

    private val occurredAt = Instant.parse("2026-08-18T00:00:00Z")
    private val leaseExpiry = occurredAt.plusSeconds(60)
    private val secretSentinel = "SECRET-TOKEN-DO-NOT-EXPORT"

    @Test
    fun `history event never exposes token and bounds error metadata`() {
        val event = LeaderAuditExportEvent.History.from(
            record = recordWithTokenAndOversizedMetadata(),
            sanitizer = LeaderAuditValueSanitizer.Default,
        )

        event::class.memberProperties.none { it.name == "token" }.shouldBeTrue()
        val errorBytes = event.errorMessage?.toByteArray(Charsets.UTF_8)?.size ?: 0
        (errorBytes <= LeaderAuditExportEvent.MAX_ERROR_MESSAGE_BYTES).shouldBeTrue()
        (event.attributes.size <= LeaderAuditExportEvent.MAX_ATTRIBUTES).shouldBeTrue()
    }

    @Test
    fun `default sanitizer redacts lock node and leader identity`() {
        val sanitizer = LeaderAuditValueSanitizer.Default

        sanitizer.sanitize(LeaderAuditField.LOCK_NAME, "tenant-42-job")
            .shouldBeEqualTo("redacted")
        sanitizer.sanitize(LeaderAuditField.LEADER_ID, "node-1")
            .shouldBeEqualTo("redacted")
    }

    @Test
    fun `default policy removes sentinel from every sensitive event field and string form`() {
        val event = LeaderAuditExportEvent.History.from(
            record = recordWithSentinelInEveryField(),
            sanitizer = LeaderAuditValueSanitizer.Default,
        )

        event.toString().contains(secretSentinel).shouldBeFalse()
        event.attributes.values.any { it.contains(secretSentinel) }.shouldBeFalse()
        event.errorMessage?.contains(secretSentinel).shouldBeFalse()
    }

    @Test
    fun `history status uses export time for unfinished expired records`() {
        val expiredRecord = LeaderLockHistoryRecord(
            lockName = "expired-lock",
            token = secretSentinel,
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = occurredAt,
            lockedUntil = Instant.now().minusSeconds(1),
            nodeId = null,
        )

        LeaderAuditExportEvent.History.from(
            record = expiredRecord,
            sanitizer = LeaderAuditValueSanitizer.Default,
        ).status.shouldBeEqualTo(LeaderHistoryStatus.EXPIRED)
    }

    @Test
    fun `utf8 bounds and attribute aggregate limits are deterministic`() {
        val event = historyWithMultibyteAndOversizedAttributes()

        ((event.errorMessage?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            <= LeaderAuditExportEvent.MAX_ERROR_MESSAGE_BYTES).shouldBeTrue()
        ((event.errorType?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            <= LeaderAuditExportEvent.MAX_ERROR_TYPE_BYTES).shouldBeTrue()
        listOf(event.lockName, event.nodeId, event.slotId).filterNotNull().all {
            it.toByteArray(Charsets.UTF_8).size <= LeaderAuditExportEvent.MAX_TEXT_FIELD_BYTES
        }.shouldBeTrue()
        val lifecycle = lifecycleWithAttributes(emptyMap(), LeaderAuditValueSanitizer.Default)
        (lifecycle.leaderId?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            .let { (it <= LeaderAuditExportEvent.MAX_TEXT_FIELD_BYTES).shouldBeTrue() }
        (event.attributes.size <= LeaderAuditExportEvent.MAX_ATTRIBUTES).shouldBeTrue()
        event.attributes.keys.all {
            it.toByteArray(Charsets.UTF_8).size <= LeaderAuditExportEvent.MAX_ATTRIBUTE_KEY_BYTES
        }.shouldBeTrue()
        event.attributes.values.all {
            it.toByteArray(Charsets.UTF_8).size <= LeaderAuditExportEvent.MAX_ATTRIBUTE_VALUE_BYTES
        }.shouldBeTrue()
        event.attributes.entries.sumOf { (key, value) ->
            key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
        }.let { (it <= LeaderAuditExportEvent.MAX_ATTRIBUTES_TOTAL_BYTES).shouldBeTrue() }

        val oversizedText = "가".repeat(200)
        val expectedText = "가".repeat(85)
        val bounded = historyWithTextFields(
            lockName = oversizedText,
            nodeId = oversizedText,
            slotId = oversizedText,
            sanitizer = LeaderAuditValueSanitizer.Truncate(
                maxBytes = LeaderAuditExportEvent.MAX_TEXT_FIELD_BYTES,
            ),
        )
        bounded.lockName.shouldBeEqualTo(expectedText)
        bounded.nodeId.shouldBeEqualTo(expectedText)
        bounded.slotId.shouldBeEqualTo(expectedText)

        val boundedLifecycle = lifecycleWithLeaderId(
            leaderId = oversizedText,
            sanitizer = LeaderAuditValueSanitizer.Truncate(
                maxBytes = LeaderAuditExportEvent.MAX_TEXT_FIELD_BYTES,
            ),
        )
        boundedLifecycle.leaderId.shouldBeEqualTo(expectedText)
    }

    @Test
    fun `utf8 truncation and sanitized collision order are deterministic`() {
        val truncated = LeaderAuditValueSanitizer.Truncate(maxBytes = 7)
            .sanitize(LeaderAuditField.ERROR_MESSAGE, "가가가x")
        truncated.shouldBeEqualTo("가가")
        truncated.contains("\uFFFD").shouldBeFalse()
        truncated.contains("...").shouldBeFalse()

        val first = linkedMapOf("a-two" to "y-value", "a-one" to "z-value")
        val second = linkedMapOf("a-one" to "z-value", "a-two" to "y-value")
        val sanitizer = LeaderAuditValueSanitizer.Truncate(maxBytes = 1)
        val firstEvent = lifecycleWithAttributes(first, sanitizer)
        val secondEvent = lifecycleWithAttributes(second, sanitizer)
        firstEvent.attributes.shouldBeEqualTo(secondEvent.attributes)
        firstEvent.attributes.values.single().shouldBeEqualTo("z")
    }

    @Test
    fun `event and attributes are immutable snapshots without public copy mutation`() {
        val source = mutableMapOf("key" to "value")
        val event = LeaderAuditExportEvent.Lifecycle.from(
            event = LeaderElectionEvent.Elected("lock", leaderId = "leader"),
            attributes = source,
            sanitizer = LeaderAuditValueSanitizer.Default,
        )

        source["key"] = "changed"
        event.attributes["redacted"].shouldBeEqualTo("redacted")
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (event.attributes as MutableMap<String, String>)["key"] = "mutated"
        }
        event::class.memberFunctions.none { it.name == "copy" }.shouldBeTrue()
    }

    @Test
    fun `hash truncate and raw modes enforce field allow list and max bytes`() {
        (LeaderAuditValueSanitizer.Hash.sanitize(LeaderAuditField.LOCK_NAME, secretSentinel) != secretSentinel)
            .shouldBeTrue()
        LeaderAuditValueSanitizer.Truncate(maxBytes = 8)
            .sanitize(LeaderAuditField.ERROR_TYPE, secretSentinel)
            .toByteArray(Charsets.UTF_8).size.shouldBeEqualTo(8)
        val raw = LeaderAuditValueSanitizer.Raw(
            allowList = setOf(LeaderAuditField.KIND),
            maxBytes = 16,
        )
        raw.sanitize(LeaderAuditField.KIND, "ACQUIRED").shouldBeEqualTo("ACQUIRED")
        LeaderAuditField.values().filter { it != LeaderAuditField.KIND }.forEach { field ->
            assertFailsWith<IllegalArgumentException> {
                raw.sanitize(field, secretSentinel)
            }
        }
    }

    @Test
    fun `raw policy remains safe when used by event factories`() {
        val raw = LeaderAuditValueSanitizer.Raw(
            allowList = setOf(LeaderAuditField.KIND),
            maxBytes = 16,
        )

        val history = LeaderAuditExportEvent.History.from(
            record = recordWithSentinelInEveryField(),
            sanitizer = raw,
        )
        val lifecycle = LeaderAuditExportEvent.Lifecycle.from(
            event = LeaderElectionEvent.Elected("lock", leaderId = secretSentinel),
            attributes = mapOf(secretSentinel to secretSentinel),
            sanitizer = raw,
        )

        history.lockName.shouldBeEqualTo("redacted")
        history.nodeId.shouldBeEqualTo("redacted")
        lifecycle.lockName.shouldBeEqualTo("redacted")
        lifecycle.leaderId.shouldBeEqualTo("redacted")
        lifecycle.toString().contains(secretSentinel).shouldBeFalse()
    }

    @Test
    fun `raw constructor rejects invalid allow lists and byte limits`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditValueSanitizer.Raw(emptySet(), maxBytes = 16)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditValueSanitizer.Raw(setOf(LeaderAuditField.LOCK_NAME), maxBytes = 16)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditValueSanitizer.Raw(
                setOf(LeaderAuditField.KIND, LeaderAuditField.LOCK_NAME),
                maxBytes = 16,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditValueSanitizer.Raw(setOf(LeaderAuditField.KIND), maxBytes = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditValueSanitizer.Raw(setOf(LeaderAuditField.KIND), maxBytes = -1)
        }
        val mutableAllowList = mutableSetOf(LeaderAuditField.KIND)
        val copied = LeaderAuditValueSanitizer.Raw(mutableAllowList, maxBytes = 16)
        mutableAllowList.clear()
        copied.sanitize(LeaderAuditField.KIND, "ACQUIRED").shouldBeEqualTo("ACQUIRED")
    }

    private fun recordWithTokenAndOversizedMetadata(): LeaderLockHistoryRecord =
        LeaderLockHistoryRecord(
            lockName = "tenant-42-job",
            token = secretSentinel,
            kind = LockIdentity.AnnotationKind.GROUP,
            acquiredAt = occurredAt,
            lockedUntil = leaseExpiry,
            nodeId = "node-1",
            finishedAt = occurredAt.plusSeconds(1),
            durationMs = 42,
            status = LeaderHistoryStatus.COMPLETED,
            errorType = "x".repeat(256),
            errorMessage = "가".repeat(5000),
            slotId = "slot-1",
            metadata = (1..64).associate { "key-$it" to "value-$it" },
        )

    private fun recordWithSentinelInEveryField(): LeaderLockHistoryRecord =
        LeaderLockHistoryRecord(
            lockName = secretSentinel,
            token = secretSentinel,
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = occurredAt,
            lockedUntil = leaseExpiry,
            nodeId = secretSentinel,
            finishedAt = occurredAt.plusSeconds(1),
            durationMs = 1,
            status = LeaderHistoryStatus.FAILED,
            errorType = secretSentinel,
            errorMessage = secretSentinel,
            slotId = secretSentinel,
            metadata = mapOf(secretSentinel to secretSentinel),
        )

    private fun historyWithMultibyteAndOversizedAttributes(): LeaderAuditExportEvent.History =
        LeaderAuditExportEvent.History.from(
            record = record(
                errorType = "가".repeat(200),
                errorMessage = "가".repeat(2000),
                metadata = (1..64).associate { "키-$it" to "값-$it".repeat(200) },
            ),
            sanitizer = LeaderAuditValueSanitizer.Truncate(maxBytes = 8192),
        )

    private fun historyWithTextFields(
        lockName: String,
        nodeId: String,
        slotId: String,
        sanitizer: LeaderAuditValueSanitizer,
    ): LeaderAuditExportEvent.History =
        LeaderAuditExportEvent.History.from(
            record = record(
                lockName = lockName,
                nodeId = nodeId,
                slotId = slotId,
            ),
            sanitizer = sanitizer,
        )

    private fun record(
        lockName: String = secretSentinel,
        nodeId: String? = secretSentinel,
        slotId: String? = secretSentinel,
        errorType: String? = secretSentinel,
        errorMessage: String? = secretSentinel,
        metadata: Map<String, String> = mapOf(secretSentinel to secretSentinel),
    ): LeaderLockHistoryRecord =
        LeaderLockHistoryRecord(
            lockName = lockName,
            token = secretSentinel,
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = occurredAt,
            lockedUntil = leaseExpiry,
            nodeId = nodeId,
            finishedAt = occurredAt.plusSeconds(1),
            durationMs = 1,
            status = LeaderHistoryStatus.FAILED,
            errorType = errorType,
            errorMessage = errorMessage,
            slotId = slotId,
            metadata = metadata,
        )

    private fun lifecycleWithAttributes(
        attributes: Map<String, String>,
        sanitizer: LeaderAuditValueSanitizer,
    ): LeaderAuditExportEvent.Lifecycle =
        LeaderAuditExportEvent.Lifecycle.from(
            event = LeaderElectionEvent.Elected(
                lockName = "lock",
                leaderId = "leader",
                leaseExpiry = leaseExpiry,
            ),
            attributes = attributes,
            sanitizer = sanitizer,
        )

    private fun lifecycleWithLeaderId(
        leaderId: String,
        sanitizer: LeaderAuditValueSanitizer,
    ): LeaderAuditExportEvent.Lifecycle =
        LeaderAuditExportEvent.Lifecycle.from(
            event = LeaderElectionEvent.Elected("lock", leaderId = leaderId),
            attributes = emptyMap(),
            sanitizer = sanitizer,
        )
}
