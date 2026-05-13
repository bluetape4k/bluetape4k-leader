package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * Immutable audit record capturing one leader-lock lifecycle event.
 *
 * ## Behavior / Contract
 * - Construction is only possible via the [companion operator invoke][LeaderLockHistoryRecord.Companion.invoke]
 *   factory; the primary constructor is private to enforce invariants.
 * - [metadata] is defensively copied on construction; further `copy()` calls are
 *   restricted by `@ConsistentCopyVisibility` — callers cannot invoke `copy()`
 *   from outside the package.
 * - [lockName] and [token] are validated non-blank by the factory.
 * - [errorMessage] is pre-truncated to [MAX_ERROR_MESSAGE_BYTES] UTF-8 bytes by
 *   [io.bluetape4k.leader.history.SafeLeaderHistoryRecorder]; raw records arriving
 *   at the sink may still be un-truncated — sinks should not assume truncation.
 * - [metadata] keys and values are limited to [MAX_METADATA_KEYS] entries and
 *   [MAX_METADATA_VALUE_LENGTH] characters respectively by the recorder layer.
 *
 * ## Security / Trust Boundary
 * The [token] field holds the live lock-release credential.  It is included in
 * the default `toString()` output — **do not log this object at INFO or above in
 * production**.  Access to the audit storage must be restricted to the same trust
 * boundary as the lock backend.  If a separate fingerprint column is needed, use
 * `Base64(SHA-256(token)).take(22)` as an opaque identifier instead.
 *
 * ## Example
 * ```kotlin
 * val record = LeaderLockHistoryRecord(
 *     lockName   = "daily-report",
 *     token      = lockHandle.token,
 *     kind       = LockIdentity.AnnotationKind.SINGLE,
 *     acquiredAt = Instant.now(),
 *     lockedUntil = Instant.now().plusSeconds(60),
 *     nodeId     = "node-1",
 * )
 * ```
 */
@ConsistentCopyVisibility
data class LeaderLockHistoryRecord private constructor(
    /** Lock name as registered with the backend. */
    val lockName: String,
    /** Live lock-release credential — see Security / Trust Boundary note above. */
    val token: String,
    /** Whether this is a single-leader or group-leader election. */
    val kind: io.bluetape4k.leader.LockIdentity.AnnotationKind,
    /** Wall-clock time when the lock was acquired. */
    val acquiredAt: Instant,
    /** Lock TTL expiry instant as reported by the backend. */
    val lockedUntil: Instant,
    /** Node/instance identifier (hostname, pod name, etc.). */
    val nodeId: String?,
    /** Wall-clock time when the action finished (null while [LeaderHistoryStatus.ACQUIRED]). */
    val finishedAt: Instant? = null,
    /** Elapsed time of the action in milliseconds (null while [LeaderHistoryStatus.ACQUIRED]). */
    val durationMs: Long? = null,
    /** Terminal status; null means still [LeaderHistoryStatus.ACQUIRED]. */
    val status: LeaderHistoryStatus? = null,
    /** Fully-qualified class name of the thrown exception, if the action failed. */
    val errorType: String? = null,
    /** Sanitized and truncated exception message, if the action failed. */
    val errorMessage: String? = null,
    /** Slot identifier for group elections (Redisson permitId or Lettuce token). */
    val slotId: String? = null,
    /** Caller-supplied key-value context; keys and values are sanitized and limited in length. */
    val metadata: Map<String, String> = emptyMap(),
) : Serializable {

    /** Returns a copy of this record with sanitized [errorMessage] and [metadata]. Internal use only. */
    internal fun withSanitizedContent(errorMessage: String?, metadata: Map<String, String>): LeaderLockHistoryRecord =
        copy(errorMessage = errorMessage, metadata = metadata)

    companion object : KLogging() {
        private const val serialVersionUID = 1L

        /** Maximum UTF-8 byte length for [errorMessage] after truncation. */
        const val MAX_ERROR_MESSAGE_BYTES = 512

        /** Maximum number of entries retained from [metadata]. */
        const val MAX_METADATA_KEYS = 16

        /** Maximum character length of a single metadata value. */
        const val MAX_METADATA_VALUE_LENGTH = 256

        /**
         * Factory function. Validates [lockName] and [token] are non-blank, and
         * defensively copies [metadata] to prevent external mutation.
         */
        operator fun invoke(
            lockName: String,
            token: String,
            kind: io.bluetape4k.leader.LockIdentity.AnnotationKind,
            acquiredAt: Instant,
            lockedUntil: Instant,
            nodeId: String? = null,
            finishedAt: Instant? = null,
            durationMs: Long? = null,
            status: LeaderHistoryStatus? = null,
            errorType: String? = null,
            errorMessage: String? = null,
            slotId: String? = null,
            metadata: Map<String, String> = emptyMap(),
        ): LeaderLockHistoryRecord {
            lockName.requireNotBlank("lockName")
            token.requireNotBlank("token")
            return LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = kind,
                acquiredAt = acquiredAt,
                lockedUntil = lockedUntil,
                nodeId = nodeId,
                finishedAt = finishedAt,
                durationMs = durationMs,
                status = status,
                errorType = errorType,
                errorMessage = errorMessage,
                slotId = slotId,
                metadata = metadata.toMap(),
            )
        }
    }
}
