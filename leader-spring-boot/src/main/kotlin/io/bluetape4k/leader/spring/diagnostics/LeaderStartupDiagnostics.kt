package io.bluetape4k.leader.spring.diagnostics

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.env.Environment
import java.io.Serializable

/**
 * `LeaderStartupDiagnostics`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
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
        val leaderElectorBeans = beanFactory.getBeanNamesForType(LeaderElector::class.java, true, false)
            .sorted()
        val nonLocalLeaderElectorBeans = leaderElectorBeans
            .filterNot { beanName -> isLocalLeaderElector(beanName) }
        val activeBackends = if (nonLocalLeaderElectorBeans.isEmpty() && leaderElectorBeans.isNotEmpty()) {
            listOf("local")
        } else {
            nonLocalLeaderElectorBeans.map(::backendNameFromBeanName)
        }
        val actuatorEndpoint = if (isManagementEndpointEnabled()) "enabled" else "disabled"
        val webExposure = managementWebExposure()
        val warnings = buildList {
            if (leaderElectorBeans.isEmpty()) {
                add(Warning(WarningCode.NO_LEADER_ELECTOR, "No LeaderElector bean is registered."))
            }
            if (nonLocalLeaderElectorBeans.size > 1) {
                add(
                    Warning(
                        WarningCode.MULTIPLE_NON_LOCAL_BACKENDS,
                        "Multiple non-local LeaderElector beans are active: ${nonLocalLeaderElectorBeans.joinToString()}. " +
                                "Use annotation bean selection or @LeaderElectionBackend.",
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
            actuatorEndpoint = actuatorEndpoint,
            webExposure = webExposure,
            strict = leaderProperties.diagnostics.strict,
            warnings = warnings,
        )
    }

    private fun isLocalLeaderElector(beanName: String): Boolean {
        val beanType = beanFactory.getType(beanName, false)
        return beanName == "localLeaderElector" ||
                beanType?.let { LocalLeaderElector::class.java.isAssignableFrom(it) } == true
    }

    private fun backendNameFromBeanName(beanName: String): String =
        beanName
            .removeSuffix("LeaderElector")
            .replace(CAMEL_BOUNDARY, "-$1")
            .lowercase()

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

    companion object : KLogging() {
        private val CAMEL_BOUNDARY = Regex("([A-Z])")
    }
}
