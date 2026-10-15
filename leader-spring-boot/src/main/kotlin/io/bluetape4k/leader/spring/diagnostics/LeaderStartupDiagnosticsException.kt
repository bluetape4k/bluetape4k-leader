package io.bluetape4k.leader.spring.diagnostics

/** strict startup diagnostics에서 하나 이상의 warning을 실패로 승격할 때 던지는 예외입니다. */
class LeaderStartupDiagnosticsException(
    warningCodes: Collection<String>,
): IllegalStateException("Leader startup diagnostics failed: ${warningCodes.joinToString()}")
