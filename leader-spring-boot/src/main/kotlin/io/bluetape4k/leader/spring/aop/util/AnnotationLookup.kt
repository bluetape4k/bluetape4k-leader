package io.bluetape4k.leader.spring.aop.util

import org.springframework.aop.support.AopUtils
import java.lang.reflect.Method

/**
 * Helper for annotation lookup in Aspect advice.
 *
 * ## [R-24] Proxy → target class fallback
 * Borrowed from ShedLock `SpringLockConfigurationExtractor.java:212-229` pattern.
 *
 * When an annotation is placed only on an interface method and the implementation is separate,
 * looking up the proxy method (interface method) directly will miss the annotation. This helper:
 * 1. First attempts `method.findMergedAnnotationOrNull<A>()`
 * 2. If not found, retries via `AopUtils.getTargetClass(target).getMethod(name, params)` on the target class method
 */
object AnnotationLookup {

    /**
     * Finds annotation [A] on [method] or on the corresponding target class method of [target].
     *
     * Kotlin idiom — reified variant. Usage:
     * ```kotlin
     * val ann = AnnotationLookup.findAnnotationWithTargetFallback<LeaderElection>(method, target)
     * ```
     *
     * @return the annotation instance, or `null` if not found
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
