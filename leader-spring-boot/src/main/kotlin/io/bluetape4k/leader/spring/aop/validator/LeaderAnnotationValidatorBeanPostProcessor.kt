package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.findMergedAnnotationOrNull
import io.bluetape4k.leader.spring.aop.util.hasMergedAnnotation
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGe
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.BeanPostProcessor
import java.lang.reflect.Method
import java.time.Duration

/**
 * `LeaderAnnotationValidatorBeanPostProcessor`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property strict Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property spel Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderAnnotationValidatorBeanPostProcessor(
    private val strict: Boolean,
    private val spel: SpelExpressionEvaluator,
) : BeanPostProcessor {

    private val validation = LeaderMethodValidationSupport(spel)

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        // [Step 3-P-Rel] reflection 자체 throw (ClassNotFound, NPE 등) 는 격리 — validation 결과는 그대로 전파
        val collected = runCatching { collectAnnotatedMethods(bean) }
            .getOrElse {
                log.warn(it) { "BPP self-throw collecting annotated methods on bean '$beanName' — validation skipped" }
                return bean
            } ?: return bean

        val (targetClass, annotated) = collected
        for (method in annotated) {
            validateMethod(method, beanName, targetClass)  // require/error 는 정당한 fail-fast — 그대로 throw
        }

        // [R-31] best-effort self-invocation WARN
        if (annotated.size >= 2) {
            log.warn {
                "leader.aop.self-inv-risk bean='$beanName' class=${targetClass.name} methods=${annotated.map { it.name }} " +
                    "(2+ annotated methods — proxy bypass via self-invocation possible)"
            }
        }
        return bean
    }

    private fun collectAnnotatedMethods(bean: Any): Pair<Class<*>, List<Method>>? {
        val targetClass = AopUtils.getTargetClass(bean)
        // Skip AOP infrastructure beans — interceptors, BPP, @Aspect classes, and Spring internals
        if (MethodInterceptor::class.java.isAssignableFrom(targetClass)) return null
        if (BeanPostProcessor::class.java.isAssignableFrom(targetClass)) return null
        if (targetClass.isAnnotationPresent(org.aspectj.lang.annotation.Aspect::class.java)) return null
        if (targetClass.`package`?.name?.startsWith("org.springframework") == true) return null

        val annotated = targetClass.declaredMethods.filter { method ->
            method.hasMergedAnnotation<LeaderElection>() || method.hasMergedAnnotation<LeaderGroupElection>()
        }
        if (annotated.isEmpty()) return null
        return targetClass to annotated
    }

    private fun validateMethod(method: Method, beanName: String, targetClass: Class<*>) {
        val leaderAnn = method.findMergedAnnotationOrNull<LeaderElection>()
        val groupAnn = method.findMergedAnnotationOrNull<LeaderGroupElection>()
        val violations = if (leaderAnn != null) {
            validation.validateSingle(
                method = method,
                beanName = beanName,
                targetClass = targetClass,
                nameExpression = leaderAnn.name,
                leaseTime = leaderAnn.leaseTime.takeIf(String::isNotBlank)?.let {
                    io.bluetape4k.leader.spring.aop.util.DurationParser.parse(it)
                } ?: Duration.ZERO,
                minLeaseTime = Duration.ZERO,
                autoExtend = leaderAnn.autoExtend,
                streamBounded = leaderAnn.streamBounded,
            ).toMutableList()
        } else {
            validation.validateMethodShape(method).toMutableList()
        }

        val returnTypeName = method.returnType.name
        if (groupAnn != null && LeaderMethodValidationSupport.isStreamReturn(returnTypeName)) {
            violations += "$returnTypeName 반환 타입 (LeaderGroupElection Flux/Flow 미지원)"
        }

        // [#84] composed 어노테이션(@AliasFor) 지원 — findMergedAnnotation 으로 합성 어노테이션 속성 해석
        leaderAnn?.let { validation.validateMinLeaseTime(it.leaseTime, it.minLeaseTime, "leader") }
        groupAnn?.let {
            // maxLeaders <= 1 은 strict 무관 항상 fail
            it.maxLeaders.requireGe(2, "group.maxLeaders")
            validation.validateMinLeaseTime(it.leaseTime, it.minLeaseTime, "group")
        }

        // SpEL pre-parse — 실패 시 strict 무관 항상 fail (잘못된 표현식은 startup 즉시 노출)
        groupAnn?.let { spel.preParse(it.name, method) }

        if (violations.isEmpty()) return

        val msg = "leader.aop.footgun bean='$beanName' method=${targetClass.name}#${method.name} " +
            "violations=${violations.joinToString("; ")}"

        if (strict) {
            error(msg)
        } else {
            log.warn { msg }
        }
    }

    companion object: KLogging()
}
