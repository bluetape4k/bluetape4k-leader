package io.bluetape4k.leader.spring.aop.util

import org.springframework.aop.support.AopUtils
import java.lang.reflect.Method

/**
 * Aspect advice 에서 어노테이션 lookup 을 위한 helper.
 *
 * ## [R-24] proxy → target class fallback
 * ShedLock `SpringLockConfigurationExtractor.java:212-229` 패턴 차용.
 *
 * 인터페이스 메서드에만 어노테이션이 부착되고 구현체는 별도일 때, 프록시 메서드 (인터페이스 메서드) 를
 * 직접 lookup 하면 어노테이션이 누락된다. 본 헬퍼는:
 * 1. `method.findMergedAnnotationOrNull<A>()` 로 1차 시도
 * 2. 미발견 시 `AopUtils.getTargetClass(target).getMethod(name, params)` 로 target class 메서드 재탐색
 */
object AnnotationLookup {

    /**
     * [method] 또는 [target] 의 target class 메서드에서 [A] 어노테이션을 찾는다.
     *
     * Kotlin idiom — reified 변형. 사용:
     * ```kotlin
     * val ann = AnnotationLookup.findAnnotationWithTargetFallback<LeaderElection>(method, target)
     * ```
     *
     * @return 어노테이션 인스턴스 또는 미발견 시 `null`
     */
    inline fun <reified A : Annotation> findAnnotationWithTargetFallback(
        method: Method,
        target: Any,
    ): A? {
        method.findMergedAnnotationOrNull<A>()?.let { return it }

        val targetClass = AopUtils.getTargetClass(target)
        if (targetClass == method.declaringClass) return null  // 동일 클래스면 추가 lookup 불필요

        val targetMethod = runCatching {
            targetClass.getMethod(method.name, *method.parameterTypes)
        }.getOrNull() ?: return null

        return targetMethod.findMergedAnnotationOrNull<A>()
    }
}
