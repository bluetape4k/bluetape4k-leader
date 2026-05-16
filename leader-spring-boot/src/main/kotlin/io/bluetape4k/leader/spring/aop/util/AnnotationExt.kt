package io.bluetape4k.leader.spring.aop.util

import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.AnnotatedElement

/**
 * Kotlin reified idiom for Spring [AnnotatedElementUtils.findMergedAnnotation].
 *
 * ## Example
 * ```kotlin
 * val leader: LeaderElection? = method.findMergedAnnotationOrNull<LeaderElection>()
 * ```
 *
 * @return The merged annotation instance, or `null` if not found
 */
inline fun <reified A : Annotation> AnnotatedElement.findMergedAnnotationOrNull(): A? =
    AnnotatedElementUtils.findMergedAnnotation(this, A::class.java)

/**
 * Kotlin reified idiom for Spring [AnnotatedElementUtils.hasAnnotation].
 *
 * ## Example
 * ```kotlin
 * if (method.hasMergedAnnotation<LeaderElection>()) { ... }
 * ```
 */
inline fun <reified A : Annotation> AnnotatedElement.hasMergedAnnotation(): Boolean =
    AnnotatedElementUtils.hasAnnotation(this, A::class.java)
