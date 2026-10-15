package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * tenant ID와 원래 lock 이름 사이에 공통 prefix를 넣어 tenant별 lock key를 만듭니다.
 * 각 구성 요소는 비어 있을 수 없고 `:`를 포함할 수 없으며, 최종 이름은 255자 이하여야 합니다.
 * @property tenantId lock key namespace를 구분하는 tenant 식별자
 * @property prefix namespace 앞에 붙일 문자열. 기본값은 `tenant`입니다.
 */
data class TenantLockNamespace(
    val tenantId: String,
    val prefix: String = DefaultPrefix,
): Serializable {

    init {
        validatePart(prefix, "prefix")
        validatePart(tenantId, "tenantId")
    }

    /**
     * `prefix:tenantId:lockName` 형식의 lock 이름을 만들고 공통 lock-name 규칙으로 검증합니다.
     * @param lockName tenant namespace 안에서 사용할 원래 lock 이름
     * @return tenant 식별자가 포함된 최대 255자의 lock 이름
     * @throws IllegalArgumentException 구성 요소가 비어 있거나 `:`를 포함하거나 최종 이름이 255자를 넘으면
     */
    fun lockName(lockName: String): String {
        validatePart(lockName, "lockName")
        val maxLockNameLength = MaxLockNameLength - prefix.length - tenantId.length - SeparatorOverhead
        require(lockName.length <= maxLockNameLength) {
            "tenant-scoped lockName is too long. maxLockNameLength=$maxLockNameLength, " +
                    "actual=${lockName.length}, prefix=$prefix, tenantId=$tenantId"
        }

        return "$prefix$Separator$tenantId$Separator$lockName".validateLockName()
    }

    private fun validatePart(value: String, name: String) {
        value.requireNotBlank(name)
        require(Separator !in value) {
            "$name must not contain '$Separator' because it is reserved as the tenant namespace separator: $value"
        }
        value.validateLockName()
    }

    companion object {
        const val DefaultPrefix: String = "tenant"
        private const val Separator = ':'
        private const val SeparatorOverhead = 2
        private const val MaxLockNameLength = 255
        private const val serialVersionUID = 1L
    }
}
