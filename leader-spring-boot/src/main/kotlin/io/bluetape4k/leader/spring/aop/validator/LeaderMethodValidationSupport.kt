package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.Duration

/**
 * annotation과 property 기반 leader policy가 공유하는 method 검증 규칙입니다.
 */
internal class LeaderMethodValidationSupport(
    private val spel: SpelExpressionEvaluator,
) {

    /** 단일 leader policy에 공통으로 적용되는 method/stream/SpEL 검증을 수행합니다. */
    @Suppress("UNUSED_PARAMETER")
    fun validateSingle(
        method: Method,
        beanName: String,
        targetClass: Class<*>,
        nameExpression: String,
        leaseTime: Duration,
        minLeaseTime: Duration,
        autoExtend: Boolean,
        streamBounded: Boolean,
    ): List<String> {
        val violations = validateMethodShape(method).toMutableList()
        if (isStreamReturn(method.returnType.name) && !autoExtend && !streamBounded) {
            violations += "${method.returnType.name} 반환 타입은 autoExtend=true 또는 streamBounded=true 필요"
        }
        require(minLeaseTime.compareTo(leaseTime) <= 0) {
            "minLeaseTime must not exceed leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
        spel.preParse(nameExpression, method)
        return violations
    }

    /** group policy처럼 stream 허용 여부가 다른 경로에서 method 공통 검증만 수행합니다. */
    fun validateMethodShape(method: Method): List<String> {
        val violations = mutableListOf<String>()

        if (Modifier.isFinal(method.modifiers)) violations += "final method (proxy 적용 불가)"
        if (Modifier.isPrivate(method.modifiers)) violations += "private method (proxy 적용 불가)"

        if (isUnsupportedFutureReturn(method.returnType)) {
            val returnTypeName = method.returnType.name
            violations += "$returnTypeName 반환 타입 (Future / CompletableFuture / ListenableFuture / Deferred — v1 미지원, " +
                "lock release 가 future 완료 전 발생 → split-brain 위험)"
        }

        return violations
    }

    /** 기존 annotation 계약의 문자열 duration 관계를 검증합니다. */
    fun validateMinLeaseTime(leaseTimeText: String, minLeaseTimeText: String, prefix: String) {
        val minLeaseTime = io.bluetape4k.leader.spring.aop.util.DurationParser
            .parseNonNegativeOrDefault(minLeaseTimeText, Duration.ZERO)
        if (minLeaseTime == Duration.ZERO) return
        if (leaseTimeText.isBlank()) return
        val leaseTime = io.bluetape4k.leader.spring.aop.util.DurationParser.parse(leaseTimeText)
        require(minLeaseTime.compareTo(leaseTime) <= 0) {
            "$prefix.minLeaseTime must not exceed $prefix.leaseTime: minLeaseTime=$minLeaseTime, leaseTime=$leaseTime"
        }
    }

    companion object {
        fun isStreamReturn(returnTypeName: String): Boolean =
            returnTypeName == "reactor.core.publisher.Flux" ||
                returnTypeName == "kotlinx.coroutines.flow.Flow"

        fun isUnsupportedFutureReturn(returnType: Class<*>): Boolean =
            java.util.concurrent.Future::class.java.isAssignableFrom(returnType) ||
                returnType.name == "kotlinx.coroutines.Deferred" ||
                runCatching {
                    val listenableFutureClass = Class.forName(
                        "com.google.common.util.concurrent.ListenableFuture",
                        false,
                        returnType.classLoader,
                    )
                    listenableFutureClass.isAssignableFrom(returnType)
                }.getOrElse { false }
    }
}
