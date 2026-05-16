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
 * BeanPostProcessor that detects footguns on methods annotated with `@LeaderElection` / `@LeaderGroupElection`.
 *
 * ## Detected issues
 * - `final` / `private` methods (proxy cannot be applied)
 * - `@LeaderGroupElection.maxLeaders` ≤ 1
 * - Unsafe stream return types (`Flux` / `Flow`) — single leader requires `autoExtend` or `streamBounded`
 * - SpEL pre-parse failure
 * - 2+ annotated methods on the same class (best-effort self-invocation WARN)
 *
 * ## Meta-annotation support (#84)
 * Also detects composed annotations built with `@AliasFor`.
 * Example: `@DailyJob` → `@LeaderElection(name = "daily-job")` composed annotation.
 * Uses [AnnotatedElementUtils.hasAnnotation] / [AnnotatedElementUtils.findMergedAnnotation].
 *
 * ## Strict mode
 * - `true`: startup fails on any violation (`maxLeaders ≤ 1` / SpEL failure always fails regardless of strict)
 * - `false` (default): WARN log only
 *
 * ## Self-throw defense [Step 3-P-Rel]
 * If reflection / SpEL calls inside the BPP itself throw (e.g. ClassNotFoundException), they are isolated
 * with `runCatching` to avoid blocking startup. Even in strict mode, internal BPP errors are WARN only.
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
     * Detects Future / CompletableFuture / ListenableFuture / Deferred return types (R12).
     *
     * - `java.util.concurrent.Future` and its sub-types (including CompletableFuture)
     * - `com.google.common.util.concurrent.ListenableFuture` (Guava optional — silently skipped if class not found)
     * - `kotlinx.coroutines.Deferred`
     *
     * When the aspect processes these in the sync branch, lock release occurs at action completion,
     * which happens before the future completes — causing a split-brain risk.
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
