package io.bluetape4k.leader.spring.diagnostics

import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.internal.LeaderElectionStateSelector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.getProperty
import java.io.Serializable

/**
 * 모든 singleton이 생성된 뒤 active backend, state provider, Actuator exposure, raw tag 위험을 검사합니다.
 *
 * @property beanFactory leader state provider와 elector bean을 조회하는 factory입니다.
 * @property environment Actuator endpoint와 web exposure 설정을 읽는 Spring environment입니다.
 * @property leaderProperties observability와 diagnostics 설정입니다.
 * @property aopProperties AOP metric tag redaction 설정입니다.
 */
class LeaderStartupDiagnostics(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val environment: Environment,
    private val leaderProperties: LeaderProperties,
    private val aopProperties: LeaderAopProperties,
): SmartInitializingSingleton {

    companion object: KLogging()

    private val stateSelector = LeaderElectionStateSelector(
        beanFactory,
        leaderProperties.observability.stateProviderBean,
    )

    @Volatile
    private var report: Report? = null

    override fun afterSingletonsInstantiated() {
        val nextReport = inspect()
        report = nextReport

        log.info {
            "leader.spring.diagnostics activeBackends=${nextReport.activeBackends} " +
                    "leaderElectors=${nextReport.leaderElectorCount} " +
                    "actuatorEndpoint=${nextReport.actuatorEndpoint} " +
                    "webExposure=${nextReport.webExposure} " +
                    "warnings=${nextReport.warningCodes.size}"
        }
        nextReport.warnings.forEach { warning ->
            log.warn { "leader.spring.diagnostics.warn code=${warning.code} message=\"${warning.message}\"" }
        }

        if (nextReport.strict && nextReport.warningCodes.isNotEmpty()) {
            throw LeaderStartupDiagnosticsException(nextReport.warningCodes)
        }
    }

    /** 마지막 startup inspection report이며 아직 검사하지 않았으면 `null`입니다. */
    fun lastReport(): Report? =
        report

    // 독립적인 설정 조건을 한 startup snapshot에서 판정해 경고 누락을 방지합니다.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun inspect(): Report {
        val stateProviderCandidates = stateSelector.candidates()
        val stateProviderBeans = stateProviderCandidates
            .map(LeaderElectionStateSelector.Candidate::beanName)
        val leaderElectorBeans = stateProviderCandidates
            .filter(LeaderElectionStateSelector.Candidate::blocking)
            .map(LeaderElectionStateSelector.Candidate::beanName)
        val activeBackends = stateProviderCandidates
            .map(LeaderElectionStateSelector.Candidate::backendName)
            .distinct()
        val selectedStateProvider = when {
            leaderProperties.observability.stateProviderBean.isNotBlank() ->
                // An explicit but invalid bean name is a configuration error; do not hide it.
                stateSelector.selectedOrNull()

            activeBackends.size > 1 ->
                // Multiple backends without an explicit choice remains a reportable warning.
                null

            else ->
                // A single backend must still surface provider creation/lookup failures.
                stateSelector.selectedOrNull()
        }
        val actuatorEndpoint = if (isManagementEndpointEnabled()) "enabled" else "disabled"
        val webExposure = managementWebExposure()
        val warnings = buildList {
            if (stateProviderBeans.isEmpty()) {
                add(Warning(WarningCode.NO_LEADER_ELECTOR, "No LeaderElectionState bean is registered."))
            }
            if (activeBackends.size > 1) {
                add(
                    Warning(
                        WarningCode.MULTIPLE_NON_LOCAL_BACKENDS,
                        "Multiple non-local leader election backends are active: ${activeBackends.joinToString()}. " +
                                "Use annotation bean selection or set " +
                                "bluetape4k.leader.observability.state-provider-bean for operational state.",
                    )
                )
            }
            if (actuatorEndpoint == "enabled" && webExposure == "hidden") {
                add(
                    Warning(
                        WarningCode.MANAGEMENT_ENDPOINT_NOT_EXPOSED,
                        "management.endpoint.leaderElection.enabled=true but " +
                                "management.endpoints.web.exposure.include " +
                                "does not include leaderElection or *.",
                    )
                )
            }
            if (actuatorEndpoint == "enabled" && leaderProperties.observability.lockNames.isEmpty()) {
                add(
                    Warning(
                        WarningCode.MANAGEMENT_REGISTRY_NOT_SEEDED,
                        "management.endpoint.leaderElection.enabled=true but " +
                                "bluetape4k.leader.observability.lock-names " +
                                "is empty. Runtime events can still populate the endpoint.",
                    )
                )
            }
            if (aopProperties.metrics.tags.lockName.mode == LeaderAopProperties.Metrics.TagMode.RAW &&
                aopProperties.metrics.tags.lockName.allowList.isEmpty()
            ) {
                add(
                    Warning(
                        WarningCode.RAW_LOCK_NAME_TAGS,
                        "Raw lock.name metric tags are enabled without an allow-list.",
                    )
                )
            }
            if (leaderProperties.observability.tracing.includeLeaderId &&
                aopProperties.metrics.tags.leaderId.mode == LeaderAopProperties.Metrics.TagMode.RAW &&
                aopProperties.metrics.tags.leaderId.allowList.isEmpty()
            ) {
                add(
                    Warning(
                        WarningCode.RAW_LEADER_ID_TAGS,
                        "Raw leader.id Observation tags are enabled without an allow-list.",
                    )
                )
            }
        }

        return Report(
            activeBackends = activeBackends,
            leaderElectorBeans = if (leaderProperties.diagnostics.includeBeanNames) {
                leaderElectorBeans
            } else {
                emptyList()
            },
            leaderElectorCount = leaderElectorBeans.size,
            stateProviderBeans = if (leaderProperties.diagnostics.includeBeanNames) stateProviderBeans else emptyList(),
            stateProviderCount = stateProviderBeans.size,
            selectedStateProviderBean = selectedStateProvider?.beanName,
            actuatorEndpoint = actuatorEndpoint,
            webExposure = webExposure,
            strict = leaderProperties.diagnostics.strict,
            warnings = warnings,
        )
    }

    private fun isManagementEndpointEnabled(): Boolean =
        environment.getProperty<Boolean>("management.endpoint.leaderElection.enabled", false)

    private fun managementWebExposure(): String {
        val include = environment.getProperty("management.endpoints.web.exposure.include")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return when {
            include.any { it == "*" || it == "leaderElection" } -> "exposed"
            else -> "hidden"
        }
    }

    /** startup diagnostics 결과입니다.
     *
     * @property activeBackends 발견된 backend 이름의 중복 제거 목록입니다.
     * @property leaderElectorBeans diagnostics 설정이 허용할 때 발견된 blocking elector bean 이름입니다.
     * @property leaderElectorCount 발견된 blocking elector 수이며 bean 이름 노출 여부와 무관하게 유지됩니다.
     * @property actuatorEndpoint `enabled` 또는 `disabled`인 leaderElection Actuator endpoint 상태입니다.
     * @property webExposure `exposed` 또는 `hidden`인 management web exposure 상태입니다.
     * @property strict warning이 있으면 startup failure로 승격하는지 여부입니다.
     * @property warnings 발견된 warning 코드와 사람이 읽을 message입니다.
     * @property stateProviderBeans diagnostics 설정이 허용할 때 발견된 state provider bean 이름입니다.
     * @property stateProviderCount 발견된 state provider 수입니다.
     * @property selectedStateProviderBean 선택된 state provider 이름이며 ambiguity 또는 미선택이면 `null`입니다.
     */
    data class Report(
        val activeBackends: List<String>,
        val leaderElectorBeans: List<String>,
        val leaderElectorCount: Int,
        val actuatorEndpoint: String,
        val webExposure: String,
        val strict: Boolean,
        val warnings: List<Warning>,
        val stateProviderBeans: List<String> = emptyList(),
        val stateProviderCount: Int = leaderElectorCount,
        val selectedStateProviderBean: String? = null,
    ): Serializable {
        val warningCodes: List<String> = warnings.map { it.code.name }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** startup diagnostics에서 보고한 하나의 warning입니다.
     *
     * @property code machine-readable warning 종류입니다.
     * @property message 동일 warning의 사람이 읽을 수 있는 설명입니다.
     */
    data class Warning(
        val code: WarningCode,
        val message: String,
    ): Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** startup diagnostics가 분류하는 warning 종류입니다. */
    enum class WarningCode {
        NO_LEADER_ELECTOR,
        MULTIPLE_NON_LOCAL_BACKENDS,
        MANAGEMENT_ENDPOINT_NOT_EXPOSED,
        MANAGEMENT_REGISTRY_NOT_SEEDED,
        RAW_LOCK_NAME_TAGS,
        RAW_LEADER_ID_TAGS,
    }
}
