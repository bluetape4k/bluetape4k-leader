package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.logging.KLogging
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException

/**
 * [LeaderBeanSelector] — explicit bean / 단일 / @Primary / ambiguous 매트릭스.
 */
class LeaderBeanSelectorTest {

    companion object: KLogging()

    private val factoryA: LeaderElectionFactory = mockk(name = "factoryA")
    private val factoryB: LeaderElectionFactory = mockk(name = "factoryB")

    @Test
    fun `explicit bean - getBean(name, type) 호출`() {
        val bf: ListableBeanFactory = mockk()
        every { bf.getBean("redissonLeaderElectionFactory", LeaderElectionFactory::class.java) } returns factoryA

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("redissonLeaderElectionFactory")

        selected.beanName shouldBeEqualTo "redissonLeaderElectionFactory"
        selected.bean shouldBeEqualTo factoryA
    }

    @Test
    fun `auto select - 단일 빈 자동 선택`() {
        val bf: ListableBeanFactory = mockk()
        every { bf.getBeansOfType(LeaderElectionFactory::class.java) } returns mapOf("only" to factoryA)

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("")

        selected.beanName shouldBeEqualTo "only"
        selected.bean shouldBeEqualTo factoryA
    }

    @Test
    fun `auto select - 0개 등록 시 NoSuchBeanDefinitionException`() {
        val bf: ListableBeanFactory = mockk()
        every { bf.getBeansOfType(LeaderElectionFactory::class.java) } returns emptyMap()

        val sut = LeaderBeanSelector(bf)
        assertThrows<NoSuchBeanDefinitionException> { sut.selectElectionFactory("") }
    }

    @Test
    fun `auto select - @Primary 후보 시 우선 선택`() {
        val bf: ListableBeanFactory = mockk()
        every { bf.getBeansOfType(LeaderElectionFactory::class.java) } returns mapOf(
            "factoryA" to factoryA,
            "factoryB" to factoryB,
        )
        every { bf.getBean(LeaderElectionFactory::class.java) } returns factoryB
        every { bf.getBeanNamesForType(LeaderElectionFactory::class.java) } returns arrayOf("factoryA", "factoryB")

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("")

        selected.beanName shouldBeEqualTo "factoryB"
        selected.bean shouldBeEqualTo factoryB
    }

    @Test
    fun `auto select - ambiguous + @Primary 없음 시 NoUniqueBeanDefinitionException`() {
        val bf: ListableBeanFactory = mockk()
        every { bf.getBeansOfType(LeaderElectionFactory::class.java) } returns mapOf(
            "factoryA" to factoryA,
            "factoryB" to factoryB,
        )
        every { bf.getBean(LeaderElectionFactory::class.java) } throws NoUniqueBeanDefinitionException(LeaderElectionFactory::class.java)

        val sut = LeaderBeanSelector(bf)
        assertThrows<NoUniqueBeanDefinitionException> { sut.selectElectionFactory("") }
    }
}
