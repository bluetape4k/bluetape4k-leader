package io.bluetape4k.leader.spring.internal

import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory

/** 선택된 state provider bean에서 backend diagnostics provider를 해석합니다. */
internal class LeaderBackendDiagnosticsSelector(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val stateProviderBean: String = "",
) {

    fun selectedOrNull(): LeaderBackendDiagnosticsProvider? {
        val selectedState = LeaderElectionStateSelector(beanFactory, stateProviderBean).selectedOrNull() ?: return null
        return when (val state = selectedState.state) {
            is LeaderBackendDiagnosticsProvider -> state
            is LeaderBackendDiagnosticsAware -> state.backendDiagnosticsProvider
            else -> null
        }
    }
}
