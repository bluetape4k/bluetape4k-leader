package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * `LockIdentity`는 lockName, token, kind, slot을 묶은 lock 소유권 식별자입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property kind single leader election인지 group leader election인지 나타내는 분류입니다.
 * @property factoryBeanName `factoryBeanName` 호출 또는 상태 계산에 필요한 값입니다.
 * @property groupParams `groupParams` 호출 또는 상태 계산에 필요한 값입니다.
 */
class LockIdentity(
    val lockName: String,
    val kind: AnnotationKind,
    /**
     * `factoryBeanName`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
     */
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
     * `equals` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param other `other` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
     * `GroupParams` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
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
