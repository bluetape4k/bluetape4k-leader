package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

/**
 * annotation 기반 leader election의 lock 이름, 선거 종류, factory와 그룹 slot 설정을 묶는 캐시 식별자입니다.
 *
 * `GROUP`이면 `groupParams`가 필요하고, `SINGLE`이면 없어야 합니다. equality와 hash code에는 factory bean 이름이 포함되지 않습니다.
 * @property lockName annotation이 지정한 lock 키
 * @property kind single 또는 group 선거 구분
 * @property factoryBeanName elector를 제공하는 factory bean의 이름
 * @property groupParams group 선거의 slot 상한; `SINGLE` 선거에는 `null`
 */
class LockIdentity(
    val lockName: String,
    val kind: AnnotationKind,
    /**
     * elector 구현체를 생성하는 Spring factory bean의 이름입니다.
     */
    val factoryBeanName: String,
    val groupParams: GroupParams? = null,
): Serializable {

    init {
        lockName.requireNotBlank("lockName")
        factoryBeanName.requireNotBlank("factoryBeanName")
        require((kind == AnnotationKind.GROUP) == (groupParams != null)) {
            "GROUP kind requires groupParams; SINGLE kind forbids it. kind=$kind, groupParams=$groupParams"
        }
    }

    /**
     * lock 이름, 선거 종류, 그룹 설정이 같은 identity인지 비교합니다. `factoryBeanName`은 비교하지 않습니다.
     * @param other 비교할 객체
     * @return `other`가 같은 lock 이름·종류·그룹 설정을 가진 `LockIdentity`이면 `true`
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

    enum class AnnotationKind {
        SINGLE,
        GROUP
    }

    /**
     * @property maxLeaders 동시에 점유할 수 있는 group leader slot의 상한; 1 이상이어야 합니다.
     */
    data class GroupParams(val maxLeaders: Int): Serializable {

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
