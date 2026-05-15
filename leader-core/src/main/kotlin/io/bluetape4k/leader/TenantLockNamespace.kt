package io.bluetape4k.leader

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Tenant namespace used to derive backend lock names from caller-facing lock names.
 *
 * ## Behavior / Contract
 * - The generated backend lock name is `prefix:tenantId:lockName`.
 * - `:` is reserved as the namespace separator and is rejected in [prefix], [tenantId], and caller lock names.
 * - The generated lock name is validated with [validateLockName] before use.
 *
 * ```kotlin
 * val namespace = TenantLockNamespace("acme")
 * namespace.lockName("report-job") // "tenant:acme:report-job"
 * ```
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
     * Returns the backend lock name for a caller-facing tenant lock name.
     *
     * @param lockName tenant-local lock name. It must not contain `:`.
     * @return backend lock name using the namespace separator.
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
