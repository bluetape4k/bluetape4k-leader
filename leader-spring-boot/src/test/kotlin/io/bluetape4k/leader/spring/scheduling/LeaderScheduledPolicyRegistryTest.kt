package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import java.lang.reflect.Method
import java.time.Duration

class LeaderScheduledPolicyRegistryTest {

    @Test
    fun `exact selector registration and target identity lookup`() {
        val firstPolicy = policy("first#reconcile", "first-lock")
        val secondPolicy = policy("second#reconcile", "second-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(firstPolicy, secondPolicy))
        val firstTarget = ScheduledFixture()
        val secondTarget = ScheduledFixture()
        val method = ScheduledFixture::class.java.getDeclaredMethod("reconcile")

        registry.register("first", firstTarget, method, firstPolicy)
        registry.register("second", secondTarget, method, secondPolicy)
        registry.freeze()

        registry.lookup(method, firstTarget)?.name shouldBeEqualTo "first-lock"
        registry.lookup(method, secondTarget)?.name shouldBeEqualTo "second-lock"
    }

    @Test
    fun `explicit annotation selector can be observed without property binding`() {
        val configured = policy("scheduled#explicit", "property-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val target = ScheduledFixture()
        val method = ScheduledFixture::class.java.getDeclaredMethod("explicit")

        registry.markObserved("scheduled#explicit")
        registry.freeze()

        registry.lookup(method, target).shouldBeNull()
    }

    @Test
    fun `duplicate selectors are rejected during construction`() {
        val configured = policy("scheduled#reconcile", "one")

        assertFailsWith<IllegalStateException> {
            LeaderScheduledPolicyRegistry(listOf(configured, configured.copy(name = "two")))
        }
    }

    @Test
    fun `overloaded selector is rejected during registration`() {
        val configured = policy("scheduled#overloaded", "overloaded-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val target = ScheduledFixture()

        registry.register(
            "scheduled",
            target,
            ScheduledFixture::class.java.getDeclaredMethod("overloaded", String::class.java),
            configured,
        )

        assertFailsWith<IllegalStateException> {
            registry.register(
                "scheduled",
                target,
                ScheduledFixture::class.java.getDeclaredMethod("overloaded", Int::class.javaPrimitiveType),
                configured,
            )
        }
    }

    @Test
    fun `unmatched selector fails when registry freezes`() {
        val registry = LeaderScheduledPolicyRegistry(listOf(policy("scheduled#missing", "missing-lock")))

        assertFailsWith<IllegalStateException> { registry.freeze() }
    }

    @Test
    fun `malformed selectors are rejected`() {
        listOf("", "scheduled", "#method", "scheduled#", "scheduled#one#two", "scheduled #method").forEach { selector ->
            assertFailsWith<IllegalArgumentException> {
                LeaderScheduledPolicyRegistry(listOf(policy(selector, "lock")))
            }
        }
    }

    @Test
    fun `interface method lookup falls back to target signature`() {
        val configured = policy("scheduled#reconcile", "interface-lock")
        val registry = LeaderScheduledPolicyRegistry(listOf(configured))
        val target = ScheduledFixture()
        val implementationMethod = ScheduledFixture::class.java.getDeclaredMethod("reconcile")
        val interfaceMethod = ScheduledContract::class.java.getDeclaredMethod("reconcile")

        registry.register("scheduled", target, implementationMethod, configured)
        registry.freeze()

        registry.lookup(interfaceMethod, target)?.name shouldBeEqualTo "interface-lock"
    }

    private fun policy(selector: String, name: String): LeaderScheduledPolicyProperties.Policy =
        LeaderScheduledPolicyProperties.Policy(
            selector = selector,
            name = name,
            waitTime = Duration.ZERO,
            leaseTime = Duration.ofSeconds(30),
            minLeaseTime = Duration.ZERO,
            bean = "leaderFactory",
            autoExtend = false,
            streamBounded = false,
            failureMode = LeaderAspectFailureMode.SKIP,
        )

    private class ScheduledFixture : ScheduledContract {
        @Scheduled(fixedDelay = Long.MAX_VALUE)
        override fun reconcile() = Unit

        @LeaderScheduled(name = "explicit-lock", fixedDelay = Long.MAX_VALUE)
        fun explicit() = Unit

        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun overloaded(value: String) = value

        @Scheduled(fixedDelay = Long.MAX_VALUE)
        fun overloaded(value: Int) = value
    }

    private interface ScheduledContract {
        fun reconcile()
    }
}
