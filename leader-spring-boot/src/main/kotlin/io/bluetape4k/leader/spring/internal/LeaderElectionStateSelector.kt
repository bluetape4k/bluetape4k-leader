package io.bluetape4k.leader.spring.internal

import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory

/**
 * Spring 운영 표면에서 조회할 [LeaderElectionState] bean을 선택합니다.
 *
 * blocking과 suspend elector를 함께 탐색하며, 비-local backend가 있으면 Spring의 local fallback은
 * 후보에서 제외합니다. 여러 backend가 남으면 `stateProviderBean`으로 하나를 명시해야 합니다.
 */
internal class LeaderElectionStateSelector(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val stateProviderBean: String = "",
) {

    fun candidates(): List<Candidate> {
        val all = beanFactory.getBeanNamesForType(LeaderElectionState::class.java, true, false)
            .sorted()
            .map { beanName ->
                val beanType = beanFactory.getType(beanName, false)
                Candidate(
                    beanName = beanName,
                    backendName = backendNameFromBeanName(beanName),
                    local = beanName == LOCAL_LEADER_ELECTOR ||
                            beanName == LOCAL_SUSPEND_LEADER_ELECTOR ||
                            beanType?.let(::isLocalType) == true,
                    blocking = beanType?.let { LeaderElector::class.java.isAssignableFrom(it) } == true,
                )
            }
        val nonLocal = all.filterNot(Candidate::local)
        return nonLocal.ifEmpty { all }
    }

    fun selectedOrNull(): Selected? =
        selectedCandidateOrNull()?.toSelected()

    fun selected(): Selected {
        val candidate = checkNotNull(selectedCandidateOrNull()) {
            "No LeaderElectionState bean is registered."
        }
        return candidate.toSelected()
    }

    private fun selectedCandidateOrNull(): Candidate? {
        val candidates = candidates()
        if (candidates.isEmpty()) return null

        return stateProviderBean.takeIf(String::isNotBlank)?.let { explicitBeanName ->
            candidates.firstOrNull { it.beanName == explicitBeanName }
                ?: throw IllegalStateException(
                    "Configured leader observability state provider '$explicitBeanName' is not an active " +
                            "LeaderElectionState bean. Candidates: ${candidates.joinToString { it.beanName }}"
                )
        } ?: selectSingleBackend(candidates)
    }

    private fun Candidate.toSelected(): Selected =
        Selected(
            beanName = beanName,
            backendName = backendName,
            state = beanFactory.getBean(beanName, LeaderElectionState::class.java),
        )

    private fun selectSingleBackend(candidates: List<Candidate>): Candidate {
        val backends = candidates.map(Candidate::backendName).distinct()
        check(backends.size == 1) {
            "Multiple leader election backends are active: ${backends.joinToString()}. " +
                    "Set bluetape4k.leader.observability.state-provider-bean to select one state provider."
        }
        return candidates.sortedWith(
            compareByDescending<Candidate>(Candidate::blocking)
                .thenBy(Candidate::beanName)
        ).first()
    }

    private fun isLocalType(type: Class<*>): Boolean =
        LocalLeaderElector::class.java.isAssignableFrom(type) ||
                LocalSuspendLeaderElector::class.java.isAssignableFrom(type)

    private fun backendNameFromBeanName(beanName: String): String =
        beanName
            .removeSuffix("SuspendLeaderElector")
            .removeSuffix("LeaderElector")
            .replace(CAMEL_BOUNDARY, "-")
            .lowercase()

    internal data class Candidate(
        val beanName: String,
        val backendName: String,
        val local: Boolean,
        val blocking: Boolean,
    )

    internal data class Selected(
        val beanName: String,
        val backendName: String,
        val state: LeaderElectionState,
    )

    private companion object {
        const val LOCAL_LEADER_ELECTOR = "localLeaderElector"
        const val LOCAL_SUSPEND_LEADER_ELECTOR = "localSuspendLeaderElector"
        val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    }
}
