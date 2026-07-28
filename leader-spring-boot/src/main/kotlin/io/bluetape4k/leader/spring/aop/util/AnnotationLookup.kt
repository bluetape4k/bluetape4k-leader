package io.bluetape4k.leader.spring.aop.util

import org.springframework.aop.support.AopUtils
import java.lang.reflect.Method

/**
 * `AnnotationLookup`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
object AnnotationLookup {

    /**
     * `선언` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
