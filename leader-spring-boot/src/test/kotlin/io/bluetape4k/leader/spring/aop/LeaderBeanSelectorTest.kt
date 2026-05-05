package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderElectionBackend
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException

/**
 * [LeaderBeanSelector] — explicit bean / 단일 / @Primary / ambiguous 매트릭스.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderBeanSelectorTest {

    companion object: KLogging()

    private val factoryA: LeaderElectorFactory = mockk(name = "factoryA")
    private val factoryB: LeaderElectorFactory = mockk(name = "factoryB")
    private val bf: ListableBeanFactory = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(factoryA, factoryB, bf)
    }

    @Test
    fun `explicit bean - getBean(name, type) 호출`() {
        every { bf.getBean("redissonLeaderElectionFactory", LeaderElectorFactory::class.java) } returns factoryA

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("redissonLeaderElectionFactory")

        selected.beanName shouldBeEqualTo "redissonLeaderElectionFactory"
        selected.bean shouldBeEqualTo factoryA
    }

    @Test
    fun `auto select - 단일 빈 자동 선택`() {
        every { bf.getBeansOfType(LeaderElectorFactory::class.java) } returns mapOf("only" to factoryA)

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("")

        selected.beanName shouldBeEqualTo "only"
        selected.bean shouldBeEqualTo factoryA
    }

    @Test
    fun `auto select - 0개 등록 시 NoSuchBeanDefinitionException`() {
        every { bf.getBeansOfType(LeaderElectorFactory::class.java) } returns emptyMap()

        val sut = LeaderBeanSelector(bf)
        assertThrows<NoSuchBeanDefinitionException> { sut.selectElectionFactory("") }
    }

    @Test
    fun `auto select - @Primary 후보 시 우선 선택`() {
        every { bf.getBeansOfType(LeaderElectorFactory::class.java) } returns mapOf(
            "factoryA" to factoryA,
            "factoryB" to factoryB,
        )
        every { bf.getBean(LeaderElectorFactory::class.java) } returns factoryB
        every { bf.getBeanNamesForType(LeaderElectorFactory::class.java) } returns arrayOf("factoryA", "factoryB")

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("")

        selected.beanName shouldBeEqualTo "factoryB"
        selected.bean shouldBeEqualTo factoryB
    }

    @Test
    fun `auto select - ambiguous + @Primary 없음 시 NoUniqueBeanDefinitionException`() {
        every { bf.getBeansOfType(LeaderElectorFactory::class.java) } returns mapOf(
            "factoryA" to factoryA,
            "factoryB" to factoryB,
        )
        every { bf.getBean(LeaderElectorFactory::class.java) } throws NoUniqueBeanDefinitionException(LeaderElectorFactory::class.java)

        val sut = LeaderBeanSelector(bf)
        assertThrows<NoUniqueBeanDefinitionException> { sut.selectElectionFactory("") }
    }

    // ── #78: @LeaderElectionBackend 탐색 테스트 ──

    private class WithMethodBackend {
        @LeaderElectionBackend("redissonFactory")
        @LeaderElection(name = "test")
        fun doWork() {}
    }

    @LeaderElectionBackend("mongoFactory")
    private class WithClassBackend {
        @LeaderElection(name = "test")
        fun doWork() {}
    }

    private class NoBackendAnnotation {
        @LeaderElection(name = "test")
        fun doWork() {}
    }

    @Test
    fun `step2 - 메서드 @LeaderElectionBackend 우선 선택`() {
        val method = WithMethodBackend::class.java.getDeclaredMethod("doWork")
        every { bf.getBean("redissonFactory", LeaderElectorFactory::class.java) } returns factoryA

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("", method)

        selected.beanName shouldBeEqualTo "redissonFactory"
        selected.bean shouldBeEqualTo factoryA
    }

    @Test
    fun `step3 - 클래스 @LeaderElectionBackend 탐색`() {
        val method = WithClassBackend::class.java.getDeclaredMethod("doWork")
        every { bf.getBean("mongoFactory", LeaderElectorFactory::class.java) } returns factoryB

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("", method)

        selected.beanName shouldBeEqualTo "mongoFactory"
        selected.bean shouldBeEqualTo factoryB
    }

    @Test
    fun `step1 우선 - explicit bean이 있으면 @LeaderElectionBackend 무시`() {
        val method = WithMethodBackend::class.java.getDeclaredMethod("doWork")
        every { bf.getBean("explicitFactory", LeaderElectorFactory::class.java) } returns factoryA

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("explicitFactory", method)

        selected.beanName shouldBeEqualTo "explicitFactory"
        selected.bean shouldBeEqualTo factoryA
    }

    @Test
    fun `@LeaderElectionBackend 없음 - 단일 빈 자동 선택으로 fallback`() {
        val method = NoBackendAnnotation::class.java.getDeclaredMethod("doWork")
        every { bf.getBeansOfType(LeaderElectorFactory::class.java) } returns mapOf("only" to factoryA)

        val sut = LeaderBeanSelector(bf)
        val selected = sut.selectElectionFactory("", method)

        selected.beanName shouldBeEqualTo "only"
        selected.bean shouldBeEqualTo factoryA
    }
}
