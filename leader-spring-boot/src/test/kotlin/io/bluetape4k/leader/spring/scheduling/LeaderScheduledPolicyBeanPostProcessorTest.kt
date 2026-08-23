package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.logging.KLogging
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.core.Ordered
import org.springframework.scheduling.annotation.Scheduled
import java.lang.reflect.Method
import java.time.Duration

class LeaderScheduledPolicyBeanPostProcessorTest {

    companion object : KLogging()

    @Test
    fun `matching plain scheduled method registers while explicit annotation only marks observed`() {
        val plainPolicy = policy("scheduled#reconcile", "plain-lock")
        val explicitPolicy = policy("scheduled#explicit", "property-lock")
        val groupPolicy = policy("scheduled#group", "group-property-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(plainPolicy, explicitPolicy, groupPolicy))
        val selector = selector()
        val target = ScheduledFixture()
        val bpp = bpp(registry, listOf(plainPolicy, explicitPolicy, groupPolicy), selector)

        bpp.postProcessAfterInitialization(target, "scheduled")
        bpp.afterSingletonsInstantiated()

        registry.lookup(method("reconcile"), target)?.name shouldBeEqualTo "plain-lock"
        registry.lookup(method("explicit"), target).shouldBeNull()
        registry.lookup(method("group"), target).shouldBeNull()
    }

    @Test
    fun `enabled policy without entries fails before context is usable`() {
        val registry = LeaderScheduledPolicyRegistry(emptyList())
        val bpp = bpp(registry, emptyList(), selector())

        assertFailsWith<IllegalStateException> { bpp.afterSingletonsInstantiated() }
    }

    @Test
    fun `selector for non scheduled method fails after singleton initialization`() {
        val configured = policy("scheduled#notScheduled", "missing-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val bpp = bpp(registry, listOf(configured), selector())

        bpp.postProcessAfterInitialization(ScheduledFixture(), "scheduled")

        assertFailsWith<IllegalStateException> { bpp.afterSingletonsInstantiated() }
    }

    @Test
    fun `blank property name fails with selector and property`() {
        val configured = policy("scheduled#reconcile", "")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val bpp = bpp(registry, listOf(configured), selector())

        val error = assertFailsWith<IllegalStateException> {
            bpp.postProcessAfterInitialization(ScheduledFixture(), "scheduled")
        }

        error.message shouldBeEqualTo "Scheduled policy 'scheduled#reconcile' property 'name' must not be blank"
    }

    @Test
    fun `negative wait time fails with selector and property`() {
        val configured = policy("scheduled#reconcile", "negative-wait", waitTime = Duration.ofSeconds(-1))
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val bpp = bpp(registry, listOf(configured), selector())

        val error = assertFailsWith<IllegalStateException> {
            bpp.postProcessAfterInitialization(ScheduledFixture(), "scheduled")
        }

        error.message shouldBeEqualTo "Scheduled policy 'scheduled#reconcile' property 'wait-time' must be zero or positive"
    }

    @Test
    fun `min lease greater than lease fails with selector and property`() {
        val configured = policy(
            "scheduled#reconcile",
            "invalid-min-lease",
            leaseTime = Duration.ofSeconds(5),
            minLeaseTime = Duration.ofSeconds(6),
        )
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val bpp = bpp(registry, listOf(configured), selector())

        val error = assertFailsWith<IllegalStateException> {
            bpp.postProcessAfterInitialization(ScheduledFixture(), "scheduled")
        }

        error.message shouldBeEqualTo
            "Scheduled policy 'scheduled#reconcile' property 'min-lease-time' must not exceed 'lease-time'"
    }

    @Test
    fun `invalid SpEL fails before scheduled policy registration`() {
        val configured = policy("scheduled#reconcile", "'unclosed")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val bpp = bpp(registry, listOf(configured), selector())

        val error = assertFailsWith<IllegalStateException> {
            bpp.postProcessAfterInitialization(ScheduledFixture(), "scheduled")
        }

        error.message.orEmpty() shouldContain "Scheduled policy 'scheduled#reconcile' property validation failed"
    }

    @Test
    fun `unresolved backend bean fails before scheduled policy registration`() {
        val configured = policy("scheduled#reconcile", "missing-backend", bean = "missingFactory")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val selector = selector()
        every { selector.selectElectionFactory("missingFactory", any()) } throws
            NoSuchBeanDefinitionException("missingFactory")
        val bpp = bpp(registry, listOf(configured), selector)

        val error = assertFailsWith<IllegalStateException> {
            bpp.postProcessAfterInitialization(ScheduledFixture(), "scheduled")
        }

        error.message.orEmpty() shouldContain "Scheduled policy 'scheduled#reconcile' property 'bean'"
    }

    @Test
    fun `policy BPP runs before lowest precedence scheduled infrastructure`() {
        val bpp = bpp(LeaderScheduledPolicyRegistry(emptyList()), emptyList(), selector())

        (bpp.order < Ordered.LOWEST_PRECEDENCE).shouldBeEqualTo(true)
    }

    private fun bpp(
        registry: LeaderScheduledPolicyRegistry,
        policies: List<LeaderScheduledPolicyProperties.Policy>,
        selector: LeaderBeanSelector,
    ): LeaderScheduledPolicyBeanPostProcessor = LeaderScheduledPolicyBeanPostProcessor(
        registry = registry,
        properties = LeaderScheduledPolicyProperties(enabled = true, policies = policies),
        aopProperties = LeaderAopProperties(),
        beanSelector = selector,
        spel = SpelExpressionEvaluator({ it }),
    )

    private fun selector(): LeaderBeanSelector {
        val selector = mockk<LeaderBeanSelector>()
        every { selector.selectElectionFactory(any(), any()) } returns LeaderBeanSelector.Selected(
            "leaderFactory",
            mockk<LeaderElectorFactory>(),
        )
        every { selector.selectSuspendElectorFactory(any(), any()) } returns LeaderBeanSelector.Selected(
            "suspendLeaderFactory",
            mockk<SuspendLeaderElectorFactory>(),
        )
        return selector
    }

    private fun method(name: String): Method = ScheduledFixture::class.java.getDeclaredMethod(name)

    private fun policy(
        selector: String,
        name: String,
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = Duration.ofSeconds(30),
        minLeaseTime: Duration = Duration.ZERO,
        bean: String = "",
    ): LeaderScheduledPolicyProperties.Policy =
        LeaderScheduledPolicyProperties.Policy(
            selector = selector,
            name = name,
            waitTime = waitTime,
            leaseTime = leaseTime,
            minLeaseTime = minLeaseTime,
            bean = bean,
            failureMode = LeaderAspectFailureMode.SKIP,
        )

    private class ScheduledFixture {
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun reconcile() = Unit

        @LeaderScheduled(name = "explicit-lock", fixedDelay = Long.MAX_VALUE)
        fun explicit() = Unit

        @LeaderGroupElection(name = "group-lock", maxLeaders = 2)
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun group() = Unit

        fun notScheduled() = Unit
    }
}
