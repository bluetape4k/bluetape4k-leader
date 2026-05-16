package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * The comparison unit for reentrant deduplication.
 *
 * Represents the full identity of a lock to determine whether the same lock is held
 * in nested calls of `@LeaderElection` / `@LeaderGroupElection`.
 *
 * ## Behavior / Contract
 * - `equals` / `hashCode` are based on `(lockName, kind, groupParams)`.
 * - **`factoryBeanName` is used only as diagnostic metadata** — excluded from `equals` (Step 3-P R3 mitigation).
 *   Designed so that reentrant pass-through works correctly even for nested calls between
 *   sync ↔ suspend or different factory beans with the same `lockName`.
 * - When `kind == GROUP`, `groupParams != null` is enforced; when `kind == SINGLE`, `groupParams == null` is enforced.
 *
 * ## Example
 * ```kotlin
 * val singleIdentity = LockIdentity(
 *     lockName = "daily-report",
 *     kind = LockIdentity.AnnotationKind.SINGLE,
 *     factoryBeanName = "lettuceLeaderElector",
 * )
 * val groupIdentity = LockIdentity(
 *     lockName = "shard-worker",
 *     kind = LockIdentity.AnnotationKind.GROUP,
 *     factoryBeanName = "lettuceLeaderGroupElector",
 *     groupParams = LockIdentity.GroupParams(maxLeaders = 3),
 * )
 * ```
 */
class LockIdentity(
    val lockName: String,
    val kind: AnnotationKind,
    /** For diagnostic metadata only — excluded from `equals/hashCode` (Step 3-P R3). */
    val factoryBeanName: String,
    val groupParams: GroupParams? = null,
) : Serializable {

    init {
        lockName.requireNotBlank("lockName")
        factoryBeanName.requireNotBlank("factoryBeanName")
        require((kind == AnnotationKind.GROUP) == (groupParams != null)) {
            "GROUP kind requires groupParams; SINGLE kind forbids it. kind=$kind, groupParams=$groupParams"
        }
    }

    /**
     * Reentrant equality — excludes `factoryBeanName`.
     *
     * In sync→suspend nested calls, treats the same lock as identical even when factory beans differ, enabling passthrough.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LockIdentity) return false
        return lockName == other.lockName &&
            kind == other.kind &&
            groupParams == other.groupParams
    }

    override fun hashCode(): Int {
        var result = lockName.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + (groupParams?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "LockIdentity(lockName='$lockName', kind=$kind, factoryBeanName='$factoryBeanName', groupParams=$groupParams)"

    enum class AnnotationKind { SINGLE, GROUP }

    /**
     * Identification parameters for a group lock.
     *
     * Currently holds only `maxLeaders`. Future additions such as slot strategy or weight
     * will be added with default values (preserving binary compatibility).
     */
    data class GroupParams(val maxLeaders: Int) : Serializable {

        init {
            maxLeaders.requirePositiveNumber("maxLeaders")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
