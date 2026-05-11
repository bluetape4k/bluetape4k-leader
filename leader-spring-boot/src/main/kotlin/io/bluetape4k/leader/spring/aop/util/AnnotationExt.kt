package io.bluetape4k.leader.spring.aop.util

import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.AnnotatedElement

/**
 * Spring [AnnotatedElementUtils.findMergedAnnotation] 의 Kotlin reified idiom.
 *
 * ## Example
 * ```kotlin
 * val leader: LeaderElection? = method.findMergedAnnotationOrNull<LeaderElection>()
 * ```
 *
 * @return 합성 어노테이션 인스턴스 또는 미발견 시 `null`
 */
inline fun <reified A : Annotation> AnnotatedElement.findMergedAnnotationOrNull(): A? =
    AnnotatedElementUtils.findMergedAnnotation(this, A::class.java)

/**
 * Spring [AnnotatedElementUtils.hasAnnotation] 의 Kotlin reified idiom.
 *
 * ## Example
 * ```kotlin
 * if (method.hasMergedAnnotation<LeaderElection>()) { ... }
 * ```
 */
inline fun <reified A : Annotation> AnnotatedElement.hasMergedAnnotation(): Boolean =
    AnnotatedElementUtils.hasAnnotation(this, A::class.java)
