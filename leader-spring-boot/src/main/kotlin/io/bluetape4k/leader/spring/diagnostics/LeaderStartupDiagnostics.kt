package io.bluetape4k.leader.spring.diagnostics

import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.internal.LeaderElectionStateSelector
import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.env.Environment
import java.io.Serializable

/**
 * `LeaderStartupDiagnostics`는 Spring Boot integration의 leader election,
 * route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property beanFactory Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property environment Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property leaderProperties Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property aopProperties Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderStartupDiagnostics(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val environment: Environment,
    private val leaderProperties: LeaderProperties,
    private val aopProperties: LeaderAopProperties,
) : SmartInitializingSingleton {

    private val stateSelector = LeaderElectionStateSelector(
        beanFactory,
        leaderProperties.observability.stateProviderBean,
    )

    @Volatile
    private var report: Report? = null

    override fun afterSingletonsInstantiated() {
        val nextReport = inspect()
        report = nextReport

        log.info(
            "leader.spring.diagnostics activeBackends=${nextReport.activeBackends} " +
                    "leaderElectors=${nextReport.leaderElectorCount} " +
                    "actuatorEndpoint=${nextReport.actuatorEndpoint} " +
                    "webExposure=${nextReport.webExposure} " +
                    "warnings=${nextReport.warningCodes.size}"
        )
        nextReport.warnings.forEach { warning ->
            log.warn("leader.spring.diagnostics.warn code=${warning.code} message=\"${warning.message}\"")
        }

        if (nextReport.strict && nextReport.warningCodes.isNotEmpty()) {
            throw LeaderStartupDiagnosticsException(nextReport.warningCodes)
        }
    }

    /**
     * `lastReport` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun lastReport(): Report? =
        report

    private fun inspect(): Report {
        val stateProviderCandidates = stateSelector.candidates()
        val stateProviderBeans = stateProviderCandidates.map(LeaderElectionStateSelector.Candidate::beanName)
        val leaderElectorBeans = stateProviderCandidates.filter(LeaderElectionStateSelector.Candidate::blocking)
            .map(LeaderElectionStateSelector.Candidate::beanName)
        val activeBackends = stateProviderCandidates.map(LeaderElectionStateSelector.Candidate::backendName).distinct()
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
                        "management.endpoint.leaderElection.enabled=true but management.endpoints.web.exposure.include " +
                                "does not include leaderElection or *.",
                    )
                )
            }
            if (actuatorEndpoint == "enabled" && leaderProperties.observability.lockNames.isEmpty()) {
                add(
                    Warning(
                        WarningCode.MANAGEMENT_REGISTRY_NOT_SEEDED,
                        "management.endpoint.leaderElection.enabled=true but bluetape4k.leader.observability.lock-names " +
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
        environment.getProperty("management.endpoint.leaderElection.enabled", Boolean::class.java, false)

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

    /**
     * `Report`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property activeBackends Spring Boot integration 계약에서 `activeBackends` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property leaderElectorBeans Spring Boot integration 계약에서 `leaderElectorBeans` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property leaderElectorCount Spring Boot integration 계약에서 `leaderElectorCount` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property stateProviderBeans blocking과 suspend 상태 provider bean 이름입니다.
     * @property stateProviderCount local fallback 제외 정책을 적용한 상태 provider 수입니다.
     * @property selectedStateProviderBean 운영 상태에 선택된 bean이며 multi-backend ambiguity에서는 null입니다.
     * @property actuatorEndpoint Spring Boot integration 계약에서 `actuatorEndpoint` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property webExposure Spring Boot integration 계약에서 `webExposure` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property strict Spring Boot integration 계약에서 `strict` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property warnings Spring Boot integration 계약에서 `warnings` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
    ) : Serializable {
        val warningCodes: List<String> = warnings.map { it.code.name }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `Warning`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property code Spring Boot integration 계약에서 `code` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property message Spring Boot integration 계약에서 `message` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Warning(
        val code: WarningCode,
        val message: String,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `WarningCode`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
     *
     * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
     */
    enum class WarningCode {
        NO_LEADER_ELECTOR,
        MULTIPLE_NON_LOCAL_BACKENDS,
        MANAGEMENT_ENDPOINT_NOT_EXPOSED,
        MANAGEMENT_REGISTRY_NOT_SEEDED,
        RAW_LOCK_NAME_TAGS,
        RAW_LEADER_ID_TAGS,
    }

    companion object : KLogging()
}
