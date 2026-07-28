package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.DurationParser
import io.bluetape4k.leader.spring.aop.util.findMergedAnnotationOrNull
import io.bluetape4k.leader.spring.aop.util.hasMergedAnnotation
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGe
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
        val violations = mutableListOf<String>()

        if (Modifier.isFinal(method.modifiers)) violations += "final method (proxy 적용 불가)"
        if (Modifier.isPrivate(method.modifiers)) violations += "private method (proxy 적용 불가)"

        val leaderAnn = method.findMergedAnnotationOrNull<LeaderElection>()
        val groupAnn = method.findMergedAnnotationOrNull<LeaderGroupElection>()

        val returnTypeName = method.returnType.name
        if (isStreamReturn(returnTypeName)) {
            if (leaderAnn != null && !leaderAnn.autoExtend && !leaderAnn.streamBounded) {
                violations += "$returnTypeName 반환 타입은 autoExtend=true 또는 streamBounded=true 필요"
            }
            if (groupAnn != null) {
                violations += "$returnTypeName 반환 타입 (LeaderGroupElection Flux/Flow 미지원)"
            }
        }
        // [#79 R12] CompletableFuture / Future / ListenableFuture / Deferred 차단
        //   aspect 가 sync 분기로 처리 → action 종료 (= release) 가 future 완료 전 발생 → split-brain 위험
        if (isUnsupportedFutureReturn(method.returnType)) {
            violations += "$returnTypeName 반환 타입 (Future / CompletableFuture / ListenableFuture / Deferred — v1 미지원, " +
                "lock release 가 future 완료 전 발생 → split-brain 위험)"
        }

        // [#84] composed 어노테이션(@AliasFor) 지원 — findMergedAnnotation 으로 합성 어노테이션 속성 해석
        leaderAnn?.let { validateMinLeaseTime(it.leaseTime, it.minLeaseTime, "leader") }
        groupAnn?.let {
            // maxLeaders <= 1 은 strict 무관 항상 fail
            it.maxLeaders.requireGe(2, "group.maxLeaders")
            validateMinLeaseTime(it.leaseTime, it.minLeaseTime, "group")
        }

        // SpEL pre-parse — 실패 시 strict 무관 항상 fail (잘못된 표현식은 startup 즉시 노출)
        leaderAnn?.let { spel.preParse(it.name, method) }
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

    private fun validateMinLeaseTime(leaseTimeText: String, minLeaseTimeText: String, prefix: String) {
        val minLeaseTime = DurationParser.parseNonNegativeOrDefault(minLeaseTimeText, java.time.Duration.ZERO)
        if (minLeaseTime == java.time.Duration.ZERO) return
        if (leaseTimeText.isBlank()) return
        val leaseTime = DurationParser.parse(leaseTimeText)
        require(minLeaseTime.compareTo(leaseTime) <= 0) {
            "$prefix.minLeaseTime must not exceed $prefix.leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    /**
     * `isUnsupportedFutureReturn` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    private fun isUnsupportedFutureReturn(returnType: Class<*>): Boolean {
        // java.util.concurrent.Future 와 그 sub-types (CompletableFuture 등)
        if (java.util.concurrent.Future::class.java.isAssignableFrom(returnType)) return true
        // kotlinx.coroutines.Deferred
        if (returnType.name == "kotlinx.coroutines.Deferred") return true
        // Guava ListenableFuture — optional dependency, Class.forName 으로 safe check
        return runCatching {
            val listenableFutureClass = Class.forName(
                "com.google.common.util.concurrent.ListenableFuture",
                false,
                returnType.classLoader,
            )
            listenableFutureClass.isAssignableFrom(returnType)
        }.getOrElse { false }
    }

    private fun isStreamReturn(returnTypeName: String): Boolean =
        returnTypeName == "reactor.core.publisher.Flux" ||
            returnTypeName == "kotlinx.coroutines.flow.Flow"

    companion object: KLogging()
}
