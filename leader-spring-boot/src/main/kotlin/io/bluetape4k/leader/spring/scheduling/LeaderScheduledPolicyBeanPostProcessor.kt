package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.findMergedAnnotationOrNull
import io.bluetape4k.leader.spring.aop.util.hasMergedAnnotation
import io.bluetape4k.leader.spring.aop.validator.LeaderMethodValidationSupport
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.MethodIntrospector
import org.springframework.core.PriorityOrdered
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Schedules
import java.lang.reflect.Method
import java.time.Duration
import kotlin.coroutines.Continuation

/**
 * `@Scheduled` method과 YAML policy의 exact selector를 startup metadata로 연결합니다.
 *
 * Spring scheduler의 task/trigger lifecycle은 소유하지 않고 policy registry만 구성합니다.
 */
class LeaderScheduledPolicyBeanPostProcessor(
    private val registry: LeaderScheduledPolicyRegistry,
    private val properties: LeaderScheduledPolicyProperties,
    private val aopProperties: LeaderAopProperties,
    private val beanSelector: LeaderBeanSelector,
    spel: SpelExpressionEvaluator,
) : BeanPostProcessor, SmartInitializingSingleton, PriorityOrdered {

    private val validation = LeaderMethodValidationSupport(spel)

    override fun getOrder(): Int = PriorityOrdered.LOWEST_PRECEDENCE - POLICY_BPP_ORDER_OFFSET

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (!properties.enabled) return bean

        val targetClass = AopUtils.getTargetClass(bean)
        if (!isInfrastructure(targetClass)) {
            collectScheduledMethods(targetClass).forEach { method ->
                val selector = "$beanName#${method.name}"
                properties.policies.firstOrNull { it.selector == selector }?.let { policy ->
                    processPolicy(bean, beanName, method, targetClass, selector, policy)
                }
            }
        }
        return bean
    }

    override fun afterSingletonsInstantiated() {
        if (!properties.enabled) return
        check(properties.policies.isNotEmpty()) {
            "Scheduled policy property 'policies' must contain at least one entry when " +
                "${LeaderScheduledPolicyProperties.PREFIX}.enabled=true"
        }
        registry.freeze()
    }

    private fun processPolicy(
        bean: Any,
        beanName: String,
        method: Method,
        targetClass: Class<*>,
        selector: String,
        policy: LeaderScheduledPolicyProperties.Policy,
    ) {
        registry.markObserved(selector)

        val explicitAnnotation = method.findMergedAnnotationOrNull<LeaderElection>()
        val explicitGroupAnnotation = method.findMergedAnnotationOrNull<LeaderGroupElection>()
        if (explicitAnnotation == null &&
            explicitGroupAnnotation == null &&
            !method.hasMergedAnnotation<LeaderScheduled>()
        ) {
            validateAndSelect(policy, selector, method, targetClass)
            registry.register(beanName, bean, method, policy)
        }
    }

    @Suppress("ThrowsCount")
    private fun validateAndSelect(
        policy: LeaderScheduledPolicyProperties.Policy,
        selector: String,
        method: Method,
        targetClass: Class<*>,
    ) {
        check(policy.name.isNotBlank()) {
            "Scheduled policy '$selector' property 'name' must not be blank"
        }

        val waitTime = policy.waitTime ?: aopProperties.defaultWaitTime
        val leaseTime = policy.leaseTime ?: aopProperties.defaultLeaseTime
        check(!waitTime.isNegative) {
            "Scheduled policy '$selector' property 'wait-time' must be zero or positive"
        }
        check(!leaseTime.isZero && !leaseTime.isNegative) {
            "Scheduled policy '$selector' property 'lease-time' must be positive"
        }
        check(!policy.minLeaseTime.isNegative) {
            "Scheduled policy '$selector' property 'min-lease-time' must be zero or positive"
        }
        check(policy.minLeaseTime <= leaseTime) {
            "Scheduled policy '$selector' property 'min-lease-time' must not exceed 'lease-time'"
        }

        val violations = try {
            validation.validateSingle(
                method = method,
                beanName = selector.substringBefore('#'),
                targetClass = targetClass,
                nameExpression = policy.name,
                leaseTime = leaseTime,
                minLeaseTime = policy.minLeaseTime,
                autoExtend = policy.autoExtend,
                streamBounded = policy.streamBounded,
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Scheduled policy '$selector' property validation failed", error)
        } catch (error: IllegalStateException) {
            throw IllegalStateException("Scheduled policy '$selector' property validation failed", error)
        }

        if (violations.isNotEmpty()) {
            val message = "Scheduled policy '$selector' property 'method' violations=${violations.joinToString("; ")}"
            if (aopProperties.strict) error(message) else log.warn { message }
        }

        try {
            beanSelector.selectElectionFactory(policy.bean, method)
            if (isSuspendOrReactive(method)) {
                beanSelector.selectSuspendElectorFactory(policy.bean, method)
            }
        } catch (error: org.springframework.beans.BeansException) {
            throw IllegalStateException(
                "Scheduled policy '$selector' property 'bean' could not select a leader factory",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException(
                "Scheduled policy '$selector' property 'bean' could not select a leader factory",
                error,
            )
        }
    }

    private fun collectScheduledMethods(targetClass: Class<*>): List<Method> =
        MethodIntrospector.selectMethods(
            targetClass,
            MethodIntrospector.MetadataLookup<Set<Scheduled>> { method ->
                AnnotatedElementUtils.getMergedRepeatableAnnotations(
                    method,
                    Scheduled::class.java,
                    Schedules::class.java,
                ).takeIf { it.isNotEmpty() }
            },
        ).keys.toList()

    private fun isInfrastructure(targetClass: Class<*>): Boolean =
        MethodInterceptor::class.java.isAssignableFrom(targetClass) ||
            BeanPostProcessor::class.java.isAssignableFrom(targetClass) ||
            targetClass.isAnnotationPresent(org.aspectj.lang.annotation.Aspect::class.java) ||
            targetClass.`package`?.name?.startsWith("org.springframework") == true

    private fun isSuspendOrReactive(method: Method): Boolean {
        val returnTypeName = method.returnType.name
        return method.parameterTypes.lastOrNull() == Continuation::class.java ||
            returnTypeName == MONO_RETURN_TYPE ||
            returnTypeName == FLUX_RETURN_TYPE ||
            returnTypeName == FLOW_RETURN_TYPE
    }

    companion object : KLogging() {
        private const val POLICY_BPP_ORDER_OFFSET = 100
        private const val MONO_RETURN_TYPE = "reactor.core.publisher.Mono"
        private const val FLUX_RETURN_TYPE = "reactor.core.publisher.Flux"
        private const val FLOW_RETURN_TYPE = "kotlinx.coroutines.flow.Flow"
    }
}
