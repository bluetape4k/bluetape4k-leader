package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.backend.LocalLeaderConfiguration
import io.bluetape4k.leader.spring.route.mvc.LeaderMvcRouteGuardFactory
import io.bluetape4k.leader.spring.route.webflux.LeaderWebFluxRouteGuardFactory
import org.springframework.beans.factory.BeanNotOfRequiredTypeException
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
 */
@AutoConfiguration(after = [LeaderElectionAutoConfiguration::class, LocalLeaderConfiguration::class])
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.route-guard",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(LeaderProperties::class)
@Import(
    LeaderRouteGuardAutoConfiguration.StateModeConfiguration::class,
    LeaderRouteGuardAutoConfiguration.CustomModeConfiguration::class,
    LeaderRouteGuardAutoConfiguration.RedirectPolicyConfiguration::class,
    LeaderRouteGuardAutoConfiguration.MvcConfiguration::class,
    LeaderRouteGuardAutoConfiguration.WebFluxConfiguration::class,
)
class LeaderRouteGuardAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.route-guard",
        name = ["authority-mode"],
        havingValue = "STATE",
        matchIfMissing = true,
    )
    class StateModeConfiguration {

        @Bean
        internal fun leaderRouteAuthorityRuntime(
            beanFactory: ListableBeanFactory,
            properties: LeaderProperties,
        ): LeaderRouteAuthorityRuntime {
            val customAuthorityNames = beanFactory
                .getBeanNamesForType(LeaderRouteAuthority::class.java, true, false)
                .sorted()
            if (customAuthorityNames.isNotEmpty()) {
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.AUTHORITY_MIXED,
                    "STATE mode does not accept custom LeaderRouteAuthority beans: $customAuthorityNames",
                )
            }

            val elector = selectElector(beanFactory, properties.routeGuard.electorBean)
            if (!elector.supportsAuditLeaderState) {
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED,
                    "STATE mode requires a LeaderElector with audit-identity state support",
                )
            }
            return LeaderRouteAuthorityRuntime(StateLeaderRouteAuthority(elector))
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.route-guard",
        name = ["authority-mode"],
        havingValue = "CUSTOM",
    )
    class CustomModeConfiguration {

        @Bean
        internal fun leaderRouteAuthorityRuntime(
            beanFactory: ListableBeanFactory,
            properties: LeaderProperties,
        ): LeaderRouteAuthorityRuntime {
            if (properties.routeGuard.electorBean.isNotBlank()) {
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.AUTHORITY_MIXED,
                    "CUSTOM mode does not accept route-guard.elector-bean",
                )
            }

            val authorityNames = beanFactory
                .getBeanNamesForType(LeaderRouteAuthority::class.java, true, false)
                .sorted()
            val authority = when (authorityNames.size) {
                0 -> throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.AUTHORITY_MISSING,
                    "CUSTOM mode requires exactly one LeaderRouteAuthority bean",
                )

                1 -> beanFactory.getBean(authorityNames.single(), LeaderRouteAuthority::class.java)
                else -> throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.AUTHORITY_AMBIGUOUS,
                    "CUSTOM mode requires exactly one LeaderRouteAuthority bean; found $authorityNames",
                )
            }
            return LeaderRouteAuthorityRuntime(authority)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.route-guard.redirect",
        name = ["enabled"],
        havingValue = "true",
    )
    class RedirectPolicyConfiguration {

        @Bean
        internal fun leaderRouteRedirectPolicy(properties: LeaderProperties): LeaderRouteRedirectPolicy =
            LeaderRouteRedirectPolicy(properties.routeGuard.redirect)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
        name = ["org.springframework.web.servlet.HandlerInterceptor", "jakarta.servlet.http.HttpServletRequest"],
    )
    class MvcConfiguration {

        @Bean
        internal fun leaderMvcRouteGuardFactory(
            runtime: LeaderRouteAuthorityRuntime,
            properties: LeaderProperties,
            redirectPolicy: ObjectProvider<LeaderRouteRedirectPolicy>,
        ): LeaderMvcRouteGuardFactory =
            LeaderMvcRouteGuardFactory(runtime, properties.routeGuard, redirectPolicy.getIfAvailable())

        /** Preserves the pre-redirect bean method descriptor for binary consumers. */
        internal fun leaderMvcRouteGuardFactory(
            runtime: LeaderRouteAuthorityRuntime,
            properties: LeaderProperties,
        ): LeaderMvcRouteGuardFactory = LeaderMvcRouteGuardFactory(runtime, properties.routeGuard)
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.web.server.WebFilter", "reactor.core.publisher.Mono"])
    class WebFluxConfiguration {

        @Bean
        internal fun leaderWebFluxRouteGuardFactory(
            runtime: LeaderRouteAuthorityRuntime,
            properties: LeaderProperties,
            redirectPolicy: ObjectProvider<LeaderRouteRedirectPolicy>,
        ): LeaderWebFluxRouteGuardFactory =
            LeaderWebFluxRouteGuardFactory(runtime, properties.routeGuard, redirectPolicy.getIfAvailable())

        /** Preserves the pre-redirect bean method descriptor for binary consumers. */
        internal fun leaderWebFluxRouteGuardFactory(
            runtime: LeaderRouteAuthorityRuntime,
            properties: LeaderProperties,
        ): LeaderWebFluxRouteGuardFactory = LeaderWebFluxRouteGuardFactory(runtime, properties.routeGuard)
    }

    companion object {
        private fun selectElector(
            beanFactory: ListableBeanFactory,
            electorBean: String,
        ): LeaderElector {
            if (electorBean.isNotBlank()) {
                return try {
                    beanFactory.getBean(electorBean, LeaderElector::class.java)
                } catch (_: BeanNotOfRequiredTypeException) {
                    throw LeaderRouteGuardConfigurationException(
                        LeaderRouteGuardConfigurationException.ELECTOR_MISSING,
                        "Bean '$electorBean' is not a LeaderElector",
                    )
                } catch (_: NoSuchBeanDefinitionException) {
                    throw LeaderRouteGuardConfigurationException(
                        LeaderRouteGuardConfigurationException.ELECTOR_MISSING,
                        "LeaderElector bean '$electorBean' was not found",
                    )
                }
            }

            return try {
                beanFactory.getBean(LeaderElector::class.java)
            } catch (e: NoUniqueBeanDefinitionException) {
                val names = e.beanNamesFound.orEmpty().sorted()
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.ELECTOR_AMBIGUOUS,
                    "STATE mode requires an explicit, unique, or primary LeaderElector bean; found $names",
                )
            } catch (_: NoSuchBeanDefinitionException) {
                throw LeaderRouteGuardConfigurationException(
                    LeaderRouteGuardConfigurationException.ELECTOR_MISSING,
                    "STATE mode requires a LeaderElector bean",
                )
            }
        }
    }
}
