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
 * leader annotation의 lock-name 문자열을 placeholder와 SpEL로 해석하고 표현식 cache를 관리합니다.
 *
 * 단순 영숫자 이름은 그대로 반환하고, 그 외 표현식은 method 인자와 target을 읽기 전용 context에서 평가합니다.
 *
 * @property embeddedValueResolver Spring `${...}` placeholder를 해석하는 resolver이며 없으면 원문을 사용합니다.
 * @property allowMethodInvocation SpEL instance method 호출 허용 여부이며 기본값은 `false`입니다.
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

    /** 표현식을 현재 메서드, 인자, target context에서 평가해 non-null lock 이름 문자열을 반환합니다. */
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

    /** 표현식을 startup validation 단계에서 parse하고 문법 오류를 `IllegalStateException`으로 보고합니다. */
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
            runCatching {
                expressionCache.get(resolved) { parser.parseExpression(it) }
            }.onFailure { ex ->
                throw IllegalStateException(
                    "Invalid SpEL expression '$resolved' on " +
                            "${method.declaringClass.name}#${method.name}: ${ex.message}",
                    ex,
                )
            }
        }
    }

    /** 현재 expression cache의 추정 항목 수를 반환합니다. */
    fun cacheSize(): Long = expressionCache.estimatedSize()

    private fun resolveplaceholder(expression: String): String =
        embeddedValueResolver?.resolveStringValue(expression) ?: expression

    private fun buildContext(method: Method, args: Array<Any?>?, target: Any?): SimpleEvaluationContext {
        val rootObject =
            if (allowMethodInvocation) RootCtx(method, args ?: emptyArray(), target)
            else RootCtx(method, args ?: emptyArray(), null)

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

    /** SpEL root object로 노출하는 호출 대상과 invocation 값입니다.
     *
     * @property method 현재 intercept된 Java method입니다.
     * @property args 호출 인자 배열이며 `a0`/`p0`와 파라미터 이름 변수로 노출됩니다.
     * @property target method target object이며 method invocation을 허용하지 않으면 `null`입니다.
     */
    @Suppress("unused")
    /** 위 root object의 data-class 구현입니다. */
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

    /** Spring expression context 타입을 optional linkage로 유지하기 위한 참조입니다. */
    @Suppress("unused")
    private fun unusedReference(): Pair<Class<*>, Class<*>> =
        MethodBasedEvaluationContext::class.java to StandardEvaluationContext::class.java

    companion object: KLogging() {
        /** placeholder 해석 후 그대로 사용할 수 있는 literal lock-name 패턴입니다. */
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")

        /** `#{...}` template expression을 식별하는 패턴입니다. */
        private val TEMPLATE_DETECT = Regex("#\\{.+}")

        /** Spring template expression parser context입니다. */
        private val TEMPLATE_PARSER_CTX = org.springframework.expression.ParserContext.TEMPLATE_EXPRESSION

        /** expression cache의 최대 항목 수인 1024입니다. */
        const val MAX_CACHE_SIZE: Long = 1024L
    }
}
