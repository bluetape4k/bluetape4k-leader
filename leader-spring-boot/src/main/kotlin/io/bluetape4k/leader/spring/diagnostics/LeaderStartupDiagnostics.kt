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
 * Startup diagnostics for leader Spring Boot auto-configuration.
 *
 * The checker inspects already-created Spring beans and bound properties only. It never opens
 * network connections to leader backends and is therefore safe to run during application startup.
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
     * Returns the most recent startup diagnostics report, or `null` before singleton initialization completes.
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
     * Immutable diagnostics summary captured after Spring singleton initialization.
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
     * Non-fatal diagnostics finding unless diagnostics strict mode is enabled.
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
     * Stable machine-readable diagnostics warning identifiers.
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
