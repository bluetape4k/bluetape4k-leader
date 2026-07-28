package io.bluetape4k.leader.spring.aop.util

import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.AnnotatedElement

/**
 * `선언` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <reified A : Annotation> AnnotatedElement.findMergedAnnotationOrNull(): A? =
    AnnotatedElementUtils.findMergedAnnotation(this, A::class.java)

/**
 * `선언` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <reified A : Annotation> AnnotatedElement.hasMergedAnnotation(): Boolean =
    AnnotatedElementUtils.hasAnnotation(this, A::class.java)
