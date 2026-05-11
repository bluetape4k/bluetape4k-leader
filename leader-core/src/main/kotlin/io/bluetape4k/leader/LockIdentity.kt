package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Reentrant dedupe 의 비교 단위.
 *
 * `@LeaderElection` / `@LeaderGroupElection` 의 nested 호출 시 동일 lock 보유 여부를 판정하기 위해
 * lock 의 full identity 를 표현합니다.
 *
 * ## 동작/계약
 * - `equals` / `hashCode` 는 `(lockName, kind, groupParams)` 기반.
 * - **`factoryBeanName` 은 진단 metadata 로만 사용** — `equals` 에서 제외 (Step 3-P R3 mitigation).
 *   동일 `lockName` 으로 sync ↔ suspend 또는 다른 factory bean 간 nested 호출 시에도 reentrant
 *   pass-through 가 정확히 동작하도록 설계.
 * - `kind == GROUP` 이면 `groupParams != null` 강제, `kind == SINGLE` 이면 `groupParams == null` 강제.
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
    /** 진단 metadata 전용 — `equals/hashCode` 에서 제외 (Step 3-P R3). */
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
     * Reentrant equality — `factoryBeanName` 제외.
     *
     * sync→suspend nested 호출 시 factory bean 이 달라도 동일 lock 으로 인식되어 passthrough.
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
     * Group lock 의 식별 파라미터.
     *
     * 현재 `maxLeaders` 만 보유. 향후 slot strategy / weight 등 추가 시 default 값으로 확장
     * (binary-compat 보존).
     */
    data class GroupParams(val maxLeaders: Int) : Serializable {

        init {
            require(maxLeaders >= 1) { "maxLeaders must be >= 1: $maxLeaders" }
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
