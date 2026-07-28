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
 * `LeaderBeanSelector`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property beanFactory Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderBeanSelector(
    private val beanFactory: BeanFactory,
) {

    /**
     * `selectElectionFactory` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun selectElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderElectorFactory> =
        select(explicitBeanName, method, LeaderElectorFactory::class.java)

    /**
     * `selectGroupElectionFactory` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun selectGroupElectionFactory(explicitBeanName: String, method: Method? = null): Selected<LeaderGroupElectorFactory> =
        select(explicitBeanName, method, LeaderGroupElectorFactory::class.java)

    /**
     * `selectSuspendElectorFactory` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun selectSuspendElectorFactory(explicitBeanName: String, method: Method? = null): Selected<SuspendLeaderElectorFactory> =
        select(explicitBeanName, method, SuspendLeaderElectorFactory::class.java)

    /**
     * `selectSuspendGroupElectorFactory` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
     * `resolveFromBackendAnnotation` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

    /**
     * `Selected`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property beanName Spring Boot integration 계약에서 `beanName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property bean Spring Boot integration 계약에서 `bean` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Selected<T>(val beanName: String, val bean: T)
}
