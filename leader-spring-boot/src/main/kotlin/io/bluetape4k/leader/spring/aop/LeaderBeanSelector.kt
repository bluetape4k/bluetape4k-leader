package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderGroupElectionFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException

/**
 * AOP advice 가 호출 단위로 사용할 [LeaderElectionFactory] / [LeaderGroupElectionFactory] 빈을 선택한다.
 *
 * ## 우선순위
 * 1. 어노테이션 `bean` 명시 → `getBean(name, Type::class)` (literal only)
 * 2. 단일 factory 빈 등록 → 그것 사용
 * 3. `@Primary` 후보 → 그것 사용
 * 4. ambiguous → [NoUniqueBeanDefinitionException] (사용자가 `bean` 필드 명시 필요)
 *
 * @param beanFactory Spring [BeanFactory]
 */
class LeaderBeanSelector(
    private val beanFactory: BeanFactory,
) {

    /**
     * 단일 리더 [LeaderElectionFactory] 빈 선택.
     *
     * @param explicitBeanName 어노테이션 `bean` 필드. 빈 문자열 시 자동 선택
     * @return 선택된 factory + 빈 이름
     * @throws NoSuchBeanDefinitionException [explicitBeanName] 이 명시되었지만 빈 미존재
     * @throws NoUniqueBeanDefinitionException 자동 선택 시 ambiguous
     */
    fun selectElectionFactory(explicitBeanName: String): Selected<LeaderElectionFactory> =
        select(explicitBeanName, LeaderElectionFactory::class.java)

    /**
     * 다중 리더 [LeaderGroupElectionFactory] 빈 선택.
     */
    fun selectGroupElectionFactory(explicitBeanName: String): Selected<LeaderGroupElectionFactory> =
        select(explicitBeanName, LeaderGroupElectionFactory::class.java)

    private fun <T : Any> select(explicitBeanName: String, type: Class<T>): Selected<T> {
        if (explicitBeanName.isNotBlank()) {
            val bean = beanFactory.getBean(explicitBeanName, type)
            return Selected(explicitBeanName, bean)
        }

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

    /** 선택된 빈 + 빈 이름 (cache key 로 사용). */
    data class Selected<T>(val beanName: String, val bean: T)
}
