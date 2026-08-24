package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAcquirerSupport
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport
import org.springframework.beans.factory.BeanNotOfRequiredTypeException
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException

/**
 * LEASE selector의 bean 선택과 capability preflight를 한 곳에서 수행합니다.
 *
 * capability 없는 wrapper는 bean 타입만 맞는다는 이유로 통과시키지 않으며, selector
 * 단계에서 고정 configuration code로 fail-closed합니다.
 */
internal class LeaseCapabilityResolver(
    private val beanFactory: ListableBeanFactory,
) {

    @Suppress("ReturnCount", "ThrowsCount")
    fun select(electorBean: String): LeaderLeaseAcquirer {
        val acquirer = if (electorBean.isNotBlank()) {
            try {
                beanFactory.getBean(electorBean, LeaderLeaseAcquirer::class.java)
            } catch (_: BeanNotOfRequiredTypeException) {
                throw unsupported("Bean '$electorBean' does not expose LeaderLeaseAcquirer")
            } catch (_: NoSuchBeanDefinitionException) {
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.ELECTOR_MISSING,
                    "Leader lease acquirer bean '$electorBean' was not found",
                )
            }
        } else {
            try {
                beanFactory.getBean(LeaderLeaseAcquirer::class.java)
            } catch (e: NoUniqueBeanDefinitionException) {
                val names = e.beanNamesFound.orEmpty().sorted()
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.ELECTOR_AMBIGUOUS,
                    "LEASE mode requires an explicit, unique, or primary LeaderLeaseAcquirer bean; found $names",
                )
            } catch (_: NoSuchBeanDefinitionException) {
                throw unsupported("LEASE mode requires a LeaderLeaseAcquirer bean")
            }
        }
        return requireLeaseCapability(acquirer)
    }

    @Suppress("ReturnCount")
    fun selectSuspend(electorBean: String): SuspendLeaderLeaseAcquirer? {
        val acquirer = if (electorBean.isNotBlank()) {
            try {
                beanFactory.getBean(electorBean, SuspendLeaderLeaseAcquirer::class.java)
            } catch (_: BeanNotOfRequiredTypeException) {
                return null
            } catch (_: NoSuchBeanDefinitionException) {
                return null
            }
        } else {
            try {
                beanFactory.getBean(SuspendLeaderLeaseAcquirer::class.java)
            } catch (_: NoUniqueBeanDefinitionException) {
                return null
            } catch (_: NoSuchBeanDefinitionException) {
                return null
            }
        }
        return requireSuspendLeaseCapability(acquirer)
    }

    private fun requireLeaseCapability(acquirer: LeaderLeaseAcquirer): LeaderLeaseAcquirer {
        if (acquirer is LeaderLeaseAcquirerSupport && !acquirer.leaseCapabilityAvailable) {
            throw unsupported("Selected elector does not expose request-lease capability")
        }
        return acquirer
    }

    private fun requireSuspendLeaseCapability(acquirer: SuspendLeaderLeaseAcquirer): SuspendLeaderLeaseAcquirer {
        if (acquirer is SuspendLeaderLeaseAcquirerSupport && !acquirer.leaseCapabilityAvailable) {
            throw LeaderRouteGuardConfigurationException(
                LeaderRouteGuardConfigurationException.LEASE_SUSPEND_UNSUPPORTED,
                "Selected elector does not expose suspend request-lease capability",
            )
        }
        return acquirer
    }

    private fun unsupported(detail: String): LeaderRouteGuardConfigurationException =
        LeaderRouteGuardConfigurationException(
            LeaderRouteGuardConfigurationException.ELECTOR_LEASE_UNSUPPORTED,
            detail,
        )
}
