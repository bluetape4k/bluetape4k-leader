package io.bluetape4k.leader.spring.aop

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyProperties
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistry
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import java.lang.reflect.Method
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

class LeaderElectionAspectScheduledPolicyTest {

    private val election: LeaderElector = mockk(relaxed = true)
    private val factory: LeaderElectorFactory = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(election, factory, beanSelector, signature, pjp)
        every { factory.create(any()) } returns election
        every { beanSelector.selectElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("propertyFactory", factory)
    }

    @Test
    fun `plain scheduled method without policy proceeds without leader calls`() {
        val target = ScheduledTarget()
        configure(target, method("plainJob"))
        every { pjp.proceed() } returns "plain"

        val aspect = newAspect()

        aspect.aroundLeader(pjp) shouldBeEqualTo "plain"
        verify(exactly = 1) { pjp.proceed() }
        verify(exactly = 0) { beanSelector.selectElectionFactory(any(), any()) }
        verify(exactly = 0) { factory.create(any()) }
    }

    @Test
    fun `property policy creates existing metadata path and skips on contention`() {
        val target = ScheduledTarget()
        val method = method("propertyJob")
        val policy = policy("scheduled#propertyJob", name = "property-lock")
        val registry = registry(policy, target, method)
        configure(target, method)
        every { pjp.proceed() } returns "property"
        every { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(registry)

        aspect.aroundLeader(pjp).shouldBeNull()
        verify(exactly = 0) { pjp.proceed() }
        verify(exactly = 1) { beanSelector.selectElectionFactory("propertyFactory", method) }
    }

    @Test
    fun `property metadata carries defaults, min lease, and failure mode`() {
        val target = ScheduledTarget()
        val method = method("propertyJob")
        val policy = policy(
            selector = "scheduled#propertyJob",
            name = "property-lock",
            waitTime = Duration.ZERO,
            leaseTime = Duration.ofSeconds(30),
            minLeaseTime = Duration.ofSeconds(5),
            failureMode = LeaderAspectFailureMode.SKIP,
        )
        val registry = registry(policy, target, method)
        configure(target, method)
        every { pjp.proceed() } returns "property"
        val options = slot<LeaderElectionOptions>()
        val lockName = slot<String>()
        val action = slot<() -> Any?>()
        every { factory.create(capture(options)) } returns election
        every { election.runIfLeaderResult(capture(lockName), capture(action)) } answers {
            LeaderRunResult.Elected(action.captured.invoke())
        }

        val result = newAspect(registry).aroundLeader(pjp)

        result shouldBeEqualTo "property"
        lockName.captured shouldBeEqualTo "property-lock"
        options.captured.waitTime shouldBeEqualTo kotlin.time.Duration.ZERO
        options.captured.leaseTime shouldBeEqualTo 30.seconds
        options.captured.minLeaseTime shouldBeEqualTo 5.seconds
    }

    @Test
    fun `explicit annotation takes precedence over conflicting property policy`() {
        val target = ScheduledTarget()
        val method = method("explicitJob")
        val property = policy("scheduled#explicitJob", name = "property-lock", bean = "propertyFactory")
        val registry = LeaderScheduledPolicyRegistry(listOf(property))
        registry.markObserved(property.selector)
        registry.freeze()
        configure(target, method)
        every { beanSelector.selectElectionFactory("annotationFactory", method) } returns
            LeaderBeanSelector.Selected("annotationFactory", factory)
        every { pjp.proceed() } returns "annotation"
        val action = slot<() -> Any?>()
        every { election.runIfLeaderResult(any<String>(), capture(action)) } answers {
            LeaderRunResult.Elected(action.captured.invoke())
        }

        val result = newAspect(registry).aroundLeader(pjp)

        result shouldBeEqualTo "annotation"
        verify(exactly = 1) { beanSelector.selectElectionFactory("annotationFactory", method) }
        verify(exactly = 0) { beanSelector.selectElectionFactory("propertyFactory", method) }
    }

    @Test
    fun `metadata cache is target aware`() {
        val firstTarget = ScheduledTarget()
        val secondTarget = ScheduledTarget()
        val method = method("propertyJob")
        val firstPolicy = policy("first#propertyJob", name = "first-lock")
        val secondPolicy = policy("second#propertyJob", name = "second-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(firstPolicy, secondPolicy))
        registry.register("first", firstTarget, method, firstPolicy)
        registry.register("second", secondTarget, method, secondPolicy)
        registry.freeze()

        every { signature.method } returns method
        every { pjp.signature } returns signature
        var currentTarget: Any = firstTarget
        every { pjp.target } answers { currentTarget }
        every { pjp.args } returns emptyArray()
        every { pjp.proceed() } answers { "body-${if (currentTarget === firstTarget) "first" else "second"}" }
        val lockNames = mutableListOf<String>()
        val lockName = slot<String>()
        val action = slot<() -> Any?>()
        every { election.runIfLeaderResult(capture(lockName), capture(action)) } answers {
            lockNames += lockName.captured
            LeaderRunResult.Elected(action.captured.invoke())
        }

        val aspect = newAspect(registry)
        aspect.aroundLeader(pjp) shouldBeEqualTo "body-first"
        currentTarget = secondTarget
        aspect.aroundLeader(pjp) shouldBeEqualTo "body-second"

        lockNames shouldBeEqualTo listOf("first-lock", "second-lock")
    }

    private fun newAspect(registry: LeaderScheduledPolicyRegistry? = null): LeaderElectionAspect =
        LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator({ it }),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
            scheduledPolicyRegistry = registry,
        )

    private fun configure(target: Any, method: Method) {
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns emptyArray()
    }

    private fun registry(
        policy: LeaderScheduledPolicyProperties.Policy,
        target: Any,
        method: Method,
    ): LeaderScheduledPolicyRegistry = LeaderScheduledPolicyRegistry(listOf(policy)).also {
        it.register("scheduled", target, method, policy)
        it.freeze()
    }

    private fun method(name: String): Method = ScheduledTarget::class.java.getDeclaredMethod(name)

    private fun policy(
        selector: String,
        name: String,
        waitTime: Duration? = Duration.ZERO,
        leaseTime: Duration? = Duration.ofSeconds(30),
        minLeaseTime: Duration = Duration.ZERO,
        bean: String = "propertyFactory",
        failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.SKIP,
    ) = LeaderScheduledPolicyProperties.Policy(
        selector = selector,
        name = name,
        waitTime = waitTime,
        leaseTime = leaseTime,
        minLeaseTime = minLeaseTime,
        bean = bean,
        failureMode = failureMode,
    )

    private class ScheduledTarget {
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun plainJob(): String = "plain"

        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun propertyJob(): String = "property"

        @LeaderElection(name = "annotation-job", bean = "annotationFactory")
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun explicitJob(): String = "annotation"
    }
}
