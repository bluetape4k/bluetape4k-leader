package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * `TenantLockNamespace`는 tenant별 lock 이름을 안정적으로 분리하는 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property tenantId tenant scope를 구분하는 식별자입니다.
 * @property prefix `prefix` 호출 또는 상태 계산에 필요한 값입니다.
 */
data class TenantLockNamespace(
    val tenantId: String,
    val prefix: String = DefaultPrefix,
) : Serializable {

    init {
        validatePart(prefix, "prefix")
        validatePart(tenantId, "tenantId")
    }

    /**
     * `lockName` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun lockName(lockName: String): String {
        validatePart(lockName, "lockName")
        val maxLockNameLength = MaxLockNameLength - prefix.length - tenantId.length - SeparatorOverhead
        require(lockName.length <= maxLockNameLength) {
            "tenant-scoped lockName is too long. maxLockNameLength=$maxLockNameLength, " +
                "actual=${lockName.length}, prefix=$prefix, tenantId=$tenantId"
        }

        return "$prefix$Separator$tenantId$Separator$lockName"
            .also(::validateLockName)
    }

    private fun validatePart(value: String, name: String) {
        value.requireNotBlank(name)
        require(Separator !in value) {
            "$name must not contain '$Separator' because it is reserved as the tenant namespace separator: $value"
        }
        validateLockName(value)
    }

    companion object {
        const val DefaultPrefix: String = "tenant"
        private const val Separator = ':'
        private const val SeparatorOverhead = 2
        private const val MaxLockNameLength = 255
        private const val serialVersionUID = 1L
    }
}
