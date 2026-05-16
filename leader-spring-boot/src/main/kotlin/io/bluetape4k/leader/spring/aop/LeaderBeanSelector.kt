package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.annotation.LeaderElectionBackend
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.aop.util.findMergedAnnotationOrNull
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import java.lang.reflect.Method

/**
 * Selects the [LeaderElectorFactory] / [LeaderGroupElectorFactory] bean to use per AOP advice invocation.
 *
 * ## Priority (#78 — 7-step fallback)
 * 1. Explicit annotation `bean` attribute (`@LeaderElection(bean = "...")`)
 * 2. Method-level `@LeaderElectionBackend`
 * 3. Declaring class `@LeaderElectionBackend`
 * 4. Package-level `@LeaderElectionBackend` (`@file:LeaderElectionBackend(...)`)
 * 5. Single factory bean
 * 6. `@Primary` factory bean
 * 7. Ambiguous → [NoUniqueBeanDefinitionException]
 *
 * @param beanFactory Spring [BeanFactory]
 */
class LeaderBeanSelector(
    private val beanFactory: BeanFactory,
) {

    /**
     * Selects a single-leader [LeaderElectorFactory] bean.
     *
     * @param explicitBeanName Annotation `bean` field. Falls back to lower-priority steps when blank.
     * @param method The invoked method — used for `@LeaderElectionBackend` lookup.
     * @return Selected factory and its bean name.
     * @throws NoSuchBeanDefinitionException If the explicitly named bean does not exist.
     * @throws NoUniqueBeanDefinitionException If auto-selection is ambiguous.
     */
    fun selectElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderElectorFactory> =
        select(explicitBeanName, method, LeaderElectorFactory::class.java)

    /**
     * Selects a multi-leader [LeaderGroupElectorFactory] bean.
     */
    fun selectGroupElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderGroupElectorFactory> =
        select(explicitBeanName, method, LeaderGroupElectorFactory::class.java)

    /**
     * Selects a suspend single-leader [SuspendLeaderElectorFactory] bean.
     */
    fun selectSuspendElectorFactory(explicitBeanName: String, method: Method? = null): Selected<SuspendLeaderElectorFactory> =
        select(explicitBeanName, method, SuspendLeaderElectorFactory::class.java)

    /**
     * Selects a suspend multi-leader [SuspendLeaderGroupElectorFactory] bean.
     */
    fun selectSuspendGroupElectorFactory(explicitBeanName: String, method: Method? = null): Selected<SuspendLeaderGroupElectorFactory> =
        select(explicitBeanName, method, SuspendLeaderGroupElectorFactory::class.java)

    private fun <T : Any> select(explicitBeanName: String, method: Method?, type: Class<T>): Selected<T> {
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
        }
    }

    /**
     * Searches for [LeaderElectionBackend] in order: method → declaring class → package.
     */
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

    /** Selected bean and its bean name (used as a cache key). */
    data class Selected<T>(val beanName: String, val bean: T)
}
