package io.bluetape4k.leader.spring.aop.util

import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.AnnotatedElement

/** Spring merged annotation을 찾아 반환하며 없으면 `null`을 반환합니다. */
inline fun <reified A: Annotation> AnnotatedElement.findMergedAnnotationOrNull(): A? =
    AnnotatedElementUtils.findMergedAnnotation(this, A::class.java)

/** Spring merged annotation이 존재하는지 반환합니다. */
inline fun <reified A: Annotation> AnnotatedElement.hasMergedAnnotation(): Boolean =
    AnnotatedElementUtils.hasAnnotation(this, A::class.java)
