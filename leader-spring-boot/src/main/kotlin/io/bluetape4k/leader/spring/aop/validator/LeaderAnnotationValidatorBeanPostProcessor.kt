package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.DurationParser
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
 * `@LeaderElection` / `@LeaderGroupElection` 부착 메서드 footgun 검출 BeanPostProcessor.
 *
 * ## 검출 항목
 * - `final` / `private` 메서드 (proxy 적용 불가)
 * - `@LeaderGroupElection.maxLeaders` ≤ 1
 * - reactive 반환 (`Flux` / `Flow`) — 미지원 (`Mono`는 #91 이후 지원)
 * - SpEL pre-parse 실패
 * - 같은 클래스에 어노테이션 부착 메서드 2+ (best-effort self-invocation WARN)
 *
 * ## 메타 어노테이션 지원 (#84)
 * `@AliasFor`로 구성된 composed 어노테이션도 검출한다.
 * 예: `@DailyJob` → `@LeaderElection(name = "daily-job")` 합성 어노테이션.
 * [AnnotatedElementUtils.hasAnnotation] / [AnnotatedElementUtils.findMergedAnnotation] 사용.
 *
 * ## strict 모드
 * - `true`: 위반 발견 시 startup fail (`maxLeaders ≤ 1` / SpEL 실패는 strict 무관 항상 fail)
 * - `false` (default): WARN 로그만
 *
 * ## 자체 throw 방어 [Step 3-P-Rel]
 * BPP 내부 reflection / SpEL 호출 자체가 throw 하면 (예: ClassNotFoundException) startup 차단 회피 위해
 * `runCatching` 으로 격리. strict 모드에서도 BPP 내부 오류는 WARN.
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
            AnnotatedElementUtils.hasAnnotation(method, LeaderElection::class.java) ||
                AnnotatedElementUtils.hasAnnotation(method, LeaderGroupElection::class.java)
        }
        if (annotated.isEmpty()) return null
        return targetClass to annotated
    }

    private fun validateMethod(method: Method, beanName: String, targetClass: Class<*>) {
        val violations = mutableListOf<String>()

        if (Modifier.isFinal(method.modifiers)) violations += "final method (proxy 적용 불가)"
        if (Modifier.isPrivate(method.modifiers)) violations += "private method (proxy 적용 불가)"

        val returnTypeName = method.returnType.name
        if (returnTypeName.endsWith(".Flux") ||
            returnTypeName == "kotlinx.coroutines.flow.Flow"
        ) {
            violations += "$returnTypeName 반환 타입 (미지원 — Mono는 #91 이후 지원, Flux/Flow 미지원)"
        }
        // [#79 R12] CompletableFuture / Future / ListenableFuture / Deferred 차단
        //   aspect 가 sync 분기로 처리 → action 종료 (= release) 가 future 완료 전 발생 → split-brain 위험
        if (isUnsupportedFutureReturn(method.returnType)) {
            violations += "$returnTypeName 반환 타입 (Future / CompletableFuture / ListenableFuture / Deferred — v1 미지원, " +
                "lock release 가 future 완료 전 발생 → split-brain 위험)"
        }

        // [#84] composed 어노테이션(@AliasFor) 지원 — findMergedAnnotation 으로 합성 어노테이션 속성 해석
        AnnotatedElementUtils.findMergedAnnotation(method, LeaderElection::class.java)?.let { leader ->
            validateMinLeaseTime(leader.leaseTime, leader.minLeaseTime, "leader")
        }

        AnnotatedElementUtils.findMergedAnnotation(method, LeaderGroupElection::class.java)?.let { group ->
            // maxLeaders <= 1 은 strict 무관 항상 fail
            group.maxLeaders.requireGe(2, "group.maxLeaders")
            validateMinLeaseTime(group.leaseTime, group.minLeaseTime, "group")
        }

        // SpEL pre-parse — 실패 시 strict 무관 항상 fail (잘못된 표현식은 startup 즉시 노출)
        AnnotatedElementUtils.findMergedAnnotation(method, LeaderElection::class.java)?.let { spel.preParse(it.name, method) }
        AnnotatedElementUtils.findMergedAnnotation(method, LeaderGroupElection::class.java)?.let { spel.preParse(it.name, method) }

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
     * Future / CompletableFuture / ListenableFuture / Deferred 반환 타입 검출 (R12).
     *
     * - `java.util.concurrent.Future` 와 sub-type (CompletableFuture 포함)
     * - `com.google.common.util.concurrent.ListenableFuture` (Guava optional — classNotFound 시 silent skip)
     * - `kotlinx.coroutines.Deferred`
     *
     * aspect 가 sync 분기로 처리 시 action 종료 시점에 lock release → future 완료 전 release 발생.
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

    companion object: KLogging()
}
