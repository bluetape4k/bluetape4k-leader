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
 * AOP advice 가 호출 단위로 사용할 [LeaderElectorFactory] / [LeaderGroupElectorFactory] 빈을 선택한다.
 *
 * ## 우선순위 (#78 — 7단계 fallback)
 * 1. 어노테이션 `bean` 명시 (`@LeaderElection(bean = "...")`)
 * 2. 메서드 `@LeaderElectionBackend`
 * 3. 선언 클래스 `@LeaderElectionBackend`
 * 4. 패키지 `@LeaderElectionBackend` (`@file:LeaderElectionBackend(...)`)
 * 5. 단일 factory 빈
 * 6. `@Primary` factory 빈
 * 7. ambiguous → [NoUniqueBeanDefinitionException]
 *
 * @param beanFactory Spring [BeanFactory]
 */
class LeaderBeanSelector(
    private val beanFactory: BeanFactory,
) {

    /**
     * 단일 리더 [LeaderElectorFactory] 빈 선택.
     *
     * @param explicitBeanName 어노테이션 `bean` 필드. 빈 문자열 시 하위 단계 탐색
     * @param method 호출 메서드 — `@LeaderElectionBackend` 탐색용
     * @return 선택된 factory + 빈 이름
     * @throws NoSuchBeanDefinitionException 명시 빈 미존재
     * @throws NoUniqueBeanDefinitionException 자동 선택 시 ambiguous
     */
    fun selectElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderElectorFactory> =
        select(explicitBeanName, method, LeaderElectorFactory::class.java)

    /**
     * 다중 리더 [LeaderGroupElectorFactory] 빈 선택.
     */
    fun selectGroupElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderGroupElectorFactory> =
        select(explicitBeanName, method, LeaderGroupElectorFactory::class.java)

    /**
     * suspend 단일 리더 [SuspendLeaderElectorFactory] 빈 선택.
     */
    fun selectSuspendElectorFactory(explicitBeanName: String, method: Method? = null): Selected<SuspendLeaderElectorFactory> =
        select(explicitBeanName, method, SuspendLeaderElectorFactory::class.java)

    /**
     * suspend 다중 리더 [SuspendLeaderGroupElectorFactory] 빈 선택.
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
     * 메서드 → 선언 클래스 → 패키지 순으로 [LeaderElectionBackend] 탐색.
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

    /** 선택된 빈 + 빈 이름 (cache key 로 사용). */
    data class Selected<T>(val beanName: String, val bean: T)
}
