package io.bluetape4k.leader.spring.aop.util

import org.springframework.aop.support.AopUtils
import java.lang.reflect.Method

/** proxy method와 target method를 순서대로 조회하는 annotation lookup 유틸리티입니다. */
object AnnotationLookup {

    /**
     * 먼저 선언 method에서 merged annotation을 찾고, proxy target class가 다르면 target method에서 재조회합니다.
     */
    // 메서드 → 대상 메서드 순서의 조기 종료로 기존 어노테이션 우선순위를 보존합니다.
    @Suppress("ReturnCount")
    inline fun <reified A: Annotation> findAnnotationWithTargetFallback(
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
