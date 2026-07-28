package io.bluetape4k.leader.spring.aop.spel

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.logging.KLogging
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.ParameterNameDiscoverer
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.DataBindingMethodResolver
import org.springframework.expression.spel.support.DataBindingPropertyAccessor
import org.springframework.expression.spel.support.SimpleEvaluationContext
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.util.StringValueResolver
import java.lang.reflect.Method
import java.time.Duration

/**
 * `SpelExpressionEvaluator`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property embeddedValueResolver Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property allowMethodInvocation Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class SpelExpressionEvaluator(
    private val embeddedValueResolver: StringValueResolver?,
    private val allowMethodInvocation: Boolean = false,
) {
    private val parser = SpelExpressionParser()
    private val parameterNameDiscoverer: ParameterNameDiscoverer = DefaultParameterNameDiscoverer()

    private val expressionCache: Cache<String, Expression> = Caffeine.newBuilder()
        .maximumSize(MAX_CACHE_SIZE)
        .expireAfterAccess(Duration.ofHours(1))
        .build()

    /**
     * `evaluate` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun evaluate(expression: String, method: Method, args: Array<Any?>, target: Any): String {
        val resolved = resolveplaceholder(expression)

        if (LITERAL_PATTERN.matches(resolved)) return resolved

        val ctx = buildContext(method, args, target)

        return if (TEMPLATE_DETECT.containsMatchIn(resolved)) {
            val parsed = expressionCache.get("T:$resolved") {
                parser.parseExpression(resolved, TEMPLATE_PARSER_CTX)
            }
            parsed.getValue(ctx, String::class.java)
                ?: error("SpEL template '$resolved' returned null at ${method.declaringClass.name}#${method.name}")
        } else {
            val parsed = expressionCache.get(resolved) { parser.parseExpression(it) }
            parsed.getValue(ctx, String::class.java)
                ?: error("SpEL '$resolved' returned null at ${method.declaringClass.name}#${method.name}")
        }
    }

    /**
     * `preParse` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun preParse(expression: String, method: Method) {
        val resolved = resolveplaceholder(expression)
        if (LITERAL_PATTERN.matches(resolved)) return

        if (TEMPLATE_DETECT.containsMatchIn(resolved)) {
            runCatching {
                expressionCache.get("T:$resolved") {
                    parser.parseExpression(resolved, TEMPLATE_PARSER_CTX)
                }
            }.onFailure { ex ->
                throw IllegalStateException(
                    "Invalid SpEL template '$resolved' on ${method.declaringClass.name}#${method.name}: ${ex.message}",
                    ex,
                )
            }
        } else {
            runCatching { expressionCache.get(resolved) { parser.parseExpression(it) } }
                .onFailure { ex ->
                    throw IllegalStateException(
                        "Invalid SpEL expression '$resolved' on ${method.declaringClass.name}#${method.name}: ${ex.message}",
                        ex,
                    )
                }
        }
    }

    /**
     * `cacheSize` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun cacheSize(): Long = expressionCache.estimatedSize()

    private fun resolveplaceholder(expression: String): String =
        embeddedValueResolver?.resolveStringValue(expression) ?: expression

    private fun buildContext(method: Method, args: Array<Any?>?, target: Any?): SimpleEvaluationContext {
        val rootObject = if (allowMethodInvocation) RootCtx(method, args ?: emptyArray(), target) else RootCtx(method, args ?: emptyArray(), null)

        val builder = SimpleEvaluationContext.forPropertyAccessors(
            DataBindingPropertyAccessor.forReadOnlyAccess(),
        )

        if (allowMethodInvocation) {
            builder.withMethodResolvers(DataBindingMethodResolver.forInstanceMethodInvocation())
        }

        val ctx = builder.withRootObject(rootObject).build()

        if (args != null) {
            val paramNames = parameterNameDiscoverer.getParameterNames(method)
            for ((index, value) in args.withIndex()) {
                ctx.setVariable("a$index", value)
                ctx.setVariable("p$index", value)
                paramNames?.getOrNull(index)?.let { ctx.setVariable(it, value) }
            }
        }

        return ctx
    }

    /**
     * `RootCtx`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property method Spring Boot integration 계약에서 `method` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property args Spring Boot integration 계약에서 `args` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property target Spring Boot integration 계약에서 `target` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    @Suppress("unused")
    /**
     * `RootCtx`는 Spring Boot integration에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
     *
     * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
     * @property method Spring Boot integration 계약에서 `method` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property args Spring Boot integration 계약에서 `args` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property target Spring Boot integration 계약에서 `target` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class RootCtx(
        val method: Method,
        val args: Array<Any?>,
        val target: Any?,
    ) {
        val methodName: String get() = method.name

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RootCtx) return false
            return method == other.method && args.contentEquals(other.args) && target == other.target
        }

        override fun hashCode(): Int {
            var result = method.hashCode()
            result = 31 * result + args.contentHashCode()
            result = 31 * result + (target?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * `unusedReference` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Suppress("unused")
    private fun unusedReference(): Pair<Class<*>, Class<*>> =
        MethodBasedEvaluationContext::class.java to StandardEvaluationContext::class.java

    companion object: KLogging() {
        /**
         * `LITERAL_PATTERN` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")

        /**
         * `TEMPLATE_DETECT` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val TEMPLATE_DETECT = Regex("#\\{.+}")

        /**
         * `TEMPLATE_PARSER_CTX` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        private val TEMPLATE_PARSER_CTX = org.springframework.expression.ParserContext.TEMPLATE_EXPRESSION

        /**
         * `MAX_CACHE_SIZE` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val MAX_CACHE_SIZE: Long = 1024L
    }
}
