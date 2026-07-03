package io.bluetape4k.leader.spring.diagnostics

/**
 * Thrown when leader startup diagnostics strict mode rejects warning conditions.
 */
class LeaderStartupDiagnosticsException(
    warningCodes: Collection<String>,
) : IllegalStateException("Leader startup diagnostics failed: ${warningCodes.joinToString()}")
