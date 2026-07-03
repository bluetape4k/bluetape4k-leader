package io.bluetape4k.leader.spring.properties

import java.io.Serializable

/**
 * Startup diagnostics options for leader Spring Boot auto-configuration.
 *
 * ## Behavior / Contract
 * - [enabled] registers the startup diagnostics checker.
 * - [strict] converts diagnostics warnings into startup failure.
 * - [includeBeanNames] includes active leader bean names in the startup summary.
 */
data class LeaderDiagnosticsProperties(
    val enabled: Boolean = true,
    val strict: Boolean = false,
    val includeBeanNames: Boolean = true,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
