package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.route.mvc.LeaderMvcRouteGuardFactory
import io.bluetape4k.leader.spring.route.webflux.LeaderWebFluxRouteGuardFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class LeaderRouteGuardAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaderRouteGuardAutoConfiguration::class.java))

    @Test
    fun `disabled default creates no route authority runtime`() {
        runner
            .withUserConfiguration(MultipleElectorsWithCustomAuthority::class.java)
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType<LeaderRouteAuthorityRuntime>().isEmpty().shouldBeTrue()
                context.getBeansOfType<StateLeaderRouteAuthority>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `state mode creates built-in authority for unique elector`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean<LeaderRouteAuthorityRuntime>().authority
                    .shouldBeInstanceOf<StateLeaderRouteAuthority>()
                context.getBeansOfType<LeaderMvcRouteGuardFactory>().size shouldBeEqualTo 1
                context.getBeansOfType<LeaderWebFluxRouteGuardFactory>().size shouldBeEqualTo 1
            }
    }

    @Test
    fun `lease mode selects additive capability and creates both route adapters`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.authority-mode=LEASE",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean<LeaderRouteLeaseRuntime>().acquirer
                    .shouldBeInstanceOf<io.bluetape4k.leader.LeaderLeaseAcquirer>()
                context.getBeansOfType<LeaderMvcRouteGuardFactory>().size shouldBeEqualTo 1
                context.getBeansOfType<LeaderWebFluxRouteGuardFactory>().size shouldBeEqualTo 1
            }
    }

    @Test
    fun `lease mode rejects redirect with a stable configuration code`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.authority-mode=LEASE",
                "bluetape4k.leader.route-guard.redirect.enabled=true",
                "bluetape4k.leader.route-guard.redirect.allowed-hosts[0]=leader.example",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.LEASE_REDIRECT_INCOMPATIBLE
            }
    }

    @Test
    fun `redirect policy bean is conditional on explicit redirect opt in`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType<LeaderRouteRedirectPolicy>().isEmpty().shouldBeTrue()
            }

        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.redirect.enabled=true",
                "bluetape4k.leader.route-guard.redirect.allowed-hosts[0]=leader.example",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType<LeaderRouteRedirectPolicy>().size shouldBeEqualTo 1
            }
    }

    @Test
    fun `outer route guard gate skips redirect semantic validation`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.redirect.enabled=true",
                "bluetape4k.leader.route-guard.redirect.allowed-hosts[0]=*.example",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType<LeaderRouteRedirectPolicy>().isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `invalid enabled redirect configuration fails at policy startup`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.redirect.enabled=true",
                "bluetape4k.leader.route-guard.redirect.allowed-hosts[0]=*.example",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `no web stack keeps authority but creates no adapter factory`() {
        runner
            .withClassLoader(FilteredClassLoader("org.springframework.web.servlet", "org.springframework.web.server"))
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBean<LeaderRouteAuthorityRuntime>().authority
                    .shouldBeInstanceOf<StateLeaderRouteAuthority>()
                context.containsBean("leaderMvcRouteGuardFactory").shouldBeFalse()
                context.containsBean("leaderWebFluxRouteGuardFactory").shouldBeFalse()
            }
    }

    @Test
    fun `mvc-only classpath creates only MVC adapter factory`() {
        runner
            .withClassLoader(FilteredClassLoader("org.springframework.web.server"))
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType<LeaderMvcRouteGuardFactory>().size shouldBeEqualTo 1
                context.containsBean("leaderWebFluxRouteGuardFactory").shouldBeFalse()
            }
    }

    @Test
    fun `webflux-only classpath creates only WebFlux adapter factory`() {
        runner
            .withClassLoader(FilteredClassLoader("org.springframework.web.servlet"))
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.containsBean("leaderMvcRouteGuardFactory").shouldBeFalse()
                context.getBeansOfType<LeaderWebFluxRouteGuardFactory>().size shouldBeEqualTo 1
            }
    }

    @Test
    fun `missing servlet API creates only WebFlux adapter factory`() {
        runner
            .withClassLoader(FilteredClassLoader("jakarta.servlet"))
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.containsBean("leaderMvcRouteGuardFactory").shouldBeFalse()
                context.getBeansOfType<LeaderWebFluxRouteGuardFactory>().size shouldBeEqualTo 1
            }
    }

    @Test
    fun `state mode plus custom authority fails as mixed configuration`() {
        runner
            .withUserConfiguration(SingleElectorWithCustomAuthority::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.AUTHORITY_MIXED
            }
    }

    @Test
    fun `state mode selects explicit elector bean`() {
        runner
            .withUserConfiguration(MultipleElectors::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.elector-bean=secondElector",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                val runtime = context.getBean<LeaderRouteAuthorityRuntime>()
                runtime.evaluate(LeaderSlot("orders", "second")) shouldBeEqualTo LeaderRouteDecision.Allowed
                runtime.evaluate(LeaderSlot("orders", "first")) shouldBeEqualTo LeaderRouteDecision.NotLeader
            }
    }

    @Test
    fun `state mode without a unique or primary elector fails as ambiguous`() {
        runner
            .withUserConfiguration(MultipleElectors::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.ELECTOR_AMBIGUOUS
            }
    }

    @Test
    fun `state mode with missing explicit elector fails as missing`() {
        runner
            .withUserConfiguration(SingleElector::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.elector-bean=missingElector",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.ELECTOR_MISSING
            }
    }

    @Test
    fun `state mode with wrong type explicit elector fails with stable missing code`() {
        runner
            .withUserConfiguration(WrongTypeElectorBean::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.elector-bean=notElector",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.ELECTOR_MISSING
            }
    }

    @Test
    fun `state mode rejects elector that inherits empty state snapshot`() {
        runner
            .withUserConfiguration(UnsupportedStateElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED
            }
    }

    @Test
    fun `state mode rejects wrapper around unsupported state elector`() {
        runner
            .withUserConfiguration(WrappedUnsupportedStateElector::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED
            }
    }

    @Test
    fun `state mode uses primary elector`() {
        runner
            .withUserConfiguration(MultipleElectorsWithPrimary::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                val runtime = context.getBean<LeaderRouteAuthorityRuntime>()
                runtime.evaluate(LeaderSlot("orders", "primary")) shouldBeEqualTo LeaderRouteDecision.Allowed
                runtime.evaluate(LeaderSlot("orders", "secondary")) shouldBeEqualTo LeaderRouteDecision.NotLeader
            }
    }

    @Test
    fun `state mode rejects custom authority using former built-in bean name`() {
        runner
            .withUserConfiguration(ReservedNameCustomAuthority::class.java)
            .withPropertyValues("bluetape4k.leader.route-guard.enabled=true")
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.AUTHORITY_MIXED
            }
    }

    @Test
    fun `custom mode requires exactly one custom authority`() {
        runner
            .withUserConfiguration(CustomAuthority::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.authority-mode=custom",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType<StateLeaderRouteAuthority>().isEmpty().shouldBeTrue()
                val runtime = context.getBean<LeaderRouteAuthorityRuntime>()
                runtime.evaluate(LeaderSlot("orders", "node-a")) shouldBeEqualTo LeaderRouteDecision.Allowed
            }
    }

    @Test
    fun `custom mode without authority fails as missing`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.authority-mode=custom",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.AUTHORITY_MISSING
            }
    }

    @Test
    fun `custom mode with multiple authorities fails as ambiguous`() {
        runner
            .withUserConfiguration(MultipleCustomAuthorities::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.authority-mode=custom",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.AUTHORITY_AMBIGUOUS
            }
    }

    @Test
    fun `custom mode plus elector bean fails as mixed configuration`() {
        runner
            .withUserConfiguration(SingleElectorWithCustomAuthority::class.java)
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.authority-mode=custom",
                "bluetape4k.leader.route-guard.elector-bean=elector",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull().message.orEmpty() shouldContain
                    LeaderRouteGuardConfigurationException.AUTHORITY_MIXED
            }
    }

    @Test
    fun `unsupported rejection status fails property binding`() {
        runner
            .withPropertyValues(
                "bluetape4k.leader.route-guard.enabled=true",
                "bluetape4k.leader.route-guard.rejection-status=FOUND",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Configuration(proxyBeanMethods = false)
    private class SingleElector {
        @Bean
        fun elector(): LeaderElector = LocalLeaderElector()
    }

    @Configuration(proxyBeanMethods = false)
    private class SingleElectorWithCustomAuthority {
        @Bean
        fun elector(): LeaderElector = LocalLeaderElector()

        @Bean
        fun customAuthority(): LeaderRouteAuthority = LeaderRouteAuthority { LeaderRouteDecision.Allowed }
    }

    @Configuration(proxyBeanMethods = false)
    private class MultipleElectors {
        @Bean
        fun firstElector(): LeaderElector = NamedStateElector("first")

        @Bean
        fun secondElector(): LeaderElector = NamedStateElector("second")
    }

    @Configuration(proxyBeanMethods = false)
    private class MultipleElectorsWithPrimary {
        @Bean
        @Primary
        fun primaryElector(): LeaderElector = NamedStateElector("primary")

        @Bean
        fun secondaryElector(): LeaderElector = NamedStateElector("secondary")
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomAuthority {
        @Bean
        fun customAuthority(): LeaderRouteAuthority = LeaderRouteAuthority { LeaderRouteDecision.Allowed }
    }

    @Configuration(proxyBeanMethods = false)
    private class MultipleCustomAuthorities {
        @Bean
        fun firstAuthority(): LeaderRouteAuthority = LeaderRouteAuthority { LeaderRouteDecision.Allowed }

        @Bean
        fun secondAuthority(): LeaderRouteAuthority = LeaderRouteAuthority { LeaderRouteDecision.NotLeader }
    }

    @Configuration(proxyBeanMethods = false)
    private class MultipleElectorsWithCustomAuthority {
        @Bean
        fun firstElector(): LeaderElector = LocalLeaderElector()

        @Bean
        fun secondElector(): LeaderElector = LocalLeaderElector()

        @Bean
        fun customAuthority(): LeaderRouteAuthority = LeaderRouteAuthority { LeaderRouteDecision.Allowed }
    }

    @Configuration(proxyBeanMethods = false)
    private class WrongTypeElectorBean {
        @Bean
        fun notElector(): String = "not-an-elector"
    }

    @Configuration(proxyBeanMethods = false)
    private class ReservedNameCustomAuthority {
        @Bean
        fun elector(): LeaderElector = LocalLeaderElector()

        @Bean("stateLeaderRouteAuthority")
        fun customAuthority(): LeaderRouteAuthority = LeaderRouteAuthority { LeaderRouteDecision.Allowed }
    }

    @Configuration(proxyBeanMethods = false)
    private class UnsupportedStateElector {
        @Bean
        fun elector(): LeaderElector = UnsupportedElector()
    }

    @Configuration(proxyBeanMethods = false)
    private class WrappedUnsupportedStateElector {
        @Bean
        fun elector(): LeaderElector = ListeningLeaderElector(UnsupportedElector())
    }

    private class NamedStateElector(
        private val auditLeaderId: String,
    ) : LeaderElector {
        override val supportsAuditLeaderState: Boolean = true

        override fun state(lockName: String): LeaderState =
            LeaderState.occupied(lockName, LeaderLease(auditLeaderId))

        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }

    private class UnsupportedElector : LeaderElector {
        override fun <T> runIfLeader(lockName: String, action: () -> T): T? = action()

        override fun <T> runAsyncIfLeader(
            lockName: String,
            executor: Executor,
            action: () -> CompletableFuture<T>,
        ): CompletableFuture<T?> = action().thenApply { it }
    }
}
