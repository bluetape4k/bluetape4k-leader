package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.spring.aop.LeaderElection
import io.bluetape4k.leader.spring.aop.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.BeanPostProcessor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * `@LeaderElection` / `@LeaderGroupElection` 부착 메서드 footgun 검출 BeanPostProcessor.
 *
 * ## 검출 항목
 * - `final` / `private` 메서드 (proxy 적용 불가)
 * - `@LeaderGroupElection.maxLeaders` ≤ 1
 * - suspend 메서드 (sync only PR — 후속 [#80])
 * - reactive 반환 (`Mono` / `Flux` / `Flow`) — sync only PR
 * - SpEL pre-parse 실패
 * - 같은 클래스에 어노테이션 부착 메서드 2+ (best-effort self-invocation WARN)
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
            method.isAnnotationPresent(LeaderElection::class.java) ||
                method.isAnnotationPresent(LeaderGroupElection::class.java)
        }
        if (annotated.isEmpty()) return null
        return targetClass to annotated
    }

    private fun validateMethod(method: Method, beanName: String, targetClass: Class<*>) {
        val violations = mutableListOf<String>()

        if (Modifier.isFinal(method.modifiers)) violations += "final method (proxy 적용 불가)"
        if (Modifier.isPrivate(method.modifiers)) violations += "private method (proxy 적용 불가)"
        if (isSuspend(method)) violations += "suspend method (sync only PR — see #80)"

        val returnTypeName = method.returnType.name
        if (returnTypeName.endsWith(".Mono") ||
            returnTypeName.endsWith(".Flux") ||
            returnTypeName == "kotlinx.coroutines.flow.Flow"
        ) {
            violations += "$returnTypeName 반환 타입 (sync only PR — see #80)"
        }

        method.getAnnotation(LeaderGroupElection::class.java)?.let { group ->
            // maxLeaders <= 1 은 strict 무관 항상 fail
            require(group.maxLeaders >= 2) {
                "@LeaderGroupElection.maxLeaders must be >= 2 on ${targetClass.name}#${method.name}, got ${group.maxLeaders}"
            }
        }

        // SpEL pre-parse — 실패 시 strict 무관 항상 fail (잘못된 표현식은 startup 즉시 노출)
        method.getAnnotation(LeaderElection::class.java)?.let { spel.preParse(it.name, method) }
        method.getAnnotation(LeaderGroupElection::class.java)?.let { spel.preParse(it.name, method) }

        if (violations.isEmpty()) return

        val msg = "leader.aop.footgun bean='$beanName' method=${targetClass.name}#${method.name} " +
            "violations=${violations.joinToString("; ")}"

        if (strict) {
            error(msg)
        } else {
            log.warn { msg }
        }
    }

    private fun isSuspend(method: Method): Boolean =
        method.parameterTypes.lastOrNull() == Continuation::class.java

    companion object: KLogging()
}
