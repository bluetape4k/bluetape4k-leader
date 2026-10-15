package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.annotation.LeaderElectionBackend
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.aop.util.findMergedAnnotationOrNull
import io.bluetape4k.leader.spring.metrics.LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME
import io.bluetape4k.leader.spring.metrics.LeaseExtensionObservationScopeOwner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.HierarchicalBeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import java.io.Serializable
import java.lang.reflect.Method

/**
 * annotation과 bean 이름을 기준으로 leader elector factory bean을 선택합니다.
 *
 * 명시된 bean 이름, 메서드ㆍ클래스ㆍ패키지의 `@LeaderElectionBackend`, 단일 bean 또는
 * Spring `@Primary` 순서로 자동 선택합니다.
 *
 * @property beanFactory 선택 대상 bean을 조회하는 Spring `BeanFactory`입니다.
 */
class LeaderBeanSelector(
    private val beanFactory: BeanFactory,
) {

    companion object: KLogging()

    internal fun observationScopeOwner(): LeaseExtensionObservationScopeOwner? =
        (beanFactory as? HierarchicalBeanFactory)
            ?.takeIf { it.containsLocalBean(LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME) }
            ?.let {
                beanFactory.getBean(
                    LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME,
                    LeaseExtensionObservationScopeOwner::class.java,
                )
            }

    /** blocking `LeaderElectorFactory`를 선택하며 bean을 찾지 못하거나 모호하면 Spring 예외를 전달합니다. */
    fun selectElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderElectorFactory> =
        select(explicitBeanName, method, LeaderElectorFactory::class.java)

    /** blocking `LeaderGroupElectorFactory`를 선택하며 bean을 찾지 못하거나 모호하면 Spring 예외를 전달합니다. */
    fun selectGroupElectionFactory(
        explicitBeanName: String,
        method: Method? = null,
    ): Selected<LeaderGroupElectorFactory> =
        select(explicitBeanName, method, LeaderGroupElectorFactory::class.java)

    /** coroutine `SuspendLeaderElectorFactory`를 선택하며 bean을 찾지 못하거나 모호하면 Spring 예외를 전달합니다. */
    fun selectSuspendElectorFactory(
        explicitBeanName: String,
        method: Method? = null,
    ): Selected<SuspendLeaderElectorFactory> =
        select(explicitBeanName, method, SuspendLeaderElectorFactory::class.java)

    /** coroutine `SuspendLeaderGroupElectorFactory`를 선택하며 bean을 찾지 못하거나 모호하면 Spring 예외를 전달합니다. */
    fun selectSuspendGroupElectorFactory(
        explicitBeanName: String,
        method: Method? = null,
    ): Selected<SuspendLeaderGroupElectorFactory> =
        select(explicitBeanName, method, SuspendLeaderGroupElectorFactory::class.java)

    // 선택 우선순위와 누락·모호성 예외를 분리해 Spring 진단 정보를 보존합니다.
    @Suppress("ReturnCount", "ThrowsCount")
    private fun <T: Any> select(explicitBeanName: String, method: Method?, type: Class<T>): Selected<T> {
        // Step 1: 어노테이션 bean 필드 명시
        if (explicitBeanName.isNotBlank()) {
            return Selected(explicitBeanName, beanFactory.getBean(explicitBeanName, type))
        }

        // Step 2-4: @LeaderElectionBackend 탐색 (메서드 → 클래스 → 패키지)
        if (method != null) {
            resolveFromBackendAnnotation(method)?.let { backendBeanName ->
                return Selected(backendBeanName, beanFactory.getBean(backendBeanName, type))
            }
        }

        // Step 5-7: 자동 선택
        val listable = beanFactory as? ListableBeanFactory
            ?: return Selected("", beanFactory.getBean(type))

        val beans: Map<String, T> = listable.getBeansOfType(type)

        return when (beans.size) {
            0 -> throw NoSuchBeanDefinitionException(type)
            1 -> {
                val (name, bean) = beans.entries.first()
                Selected(name, bean)
            }
            else -> {
                val primaryBean = runCatching { beanFactory.getBean(type) }.getOrNull()
                if (primaryBean != null) {
                    val primaryName = listable.getBeanNamesForType(type)
                        .firstOrNull { beans[it] === primaryBean }
                        ?: throw NoUniqueBeanDefinitionException(type, beans.keys)
                    Selected(primaryName, primaryBean)
                } else {
                    throw NoUniqueBeanDefinitionException(type, beans.keys)
                }
            }
        }.apply {
            log.debug { "select $this, explicitBeanName=$explicitBeanName, method=$method, type=$type" }
        }
    }

    /** 메서드, 선언 클래스, 패키지에 붙은 `@LeaderElectionBackend`의 첫 번째 비어 있지 않은 bean 이름을 반환합니다. */
    private fun resolveFromBackendAnnotation(method: Method): String? {
        // Step 2: 메서드 @LeaderElectionBackend
        method.findMergedAnnotationOrNull<LeaderElectionBackend>()
            ?.bean?.takeIf { it.isNotBlank() }?.let { return it }

        val declaringClass = method.declaringClass

        // Step 3: 선언 클래스 @LeaderElectionBackend
        declaringClass.findMergedAnnotationOrNull<LeaderElectionBackend>()
            ?.bean?.takeIf { it.isNotBlank() }?.let { return it }

        // Step 4: 패키지 @LeaderElectionBackend (@file:LeaderElectionBackend)
        declaringClass.`package`?.annotations
            ?.filterIsInstance<LeaderElectionBackend>()
            ?.firstOrNull()
            ?.bean?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    /** 선택 결과로 사용한 Spring bean 이름과 실제 factory 인스턴스입니다.
     *
     * @property beanName 선택된 bean 이름이며 bean type으로 자동 선택한 경우에도 실제 이름을 보존합니다.
     * @property bean 선택된 factory bean 인스턴스입니다.
     */
    data class Selected<T>(val beanName: String, val bean: T): Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
