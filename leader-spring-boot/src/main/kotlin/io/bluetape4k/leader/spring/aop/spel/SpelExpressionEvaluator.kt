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
 * Plain SpEL / Template SpEL evaluator for `@LeaderElection.name` / `@LeaderGroupElection.name`.
 *
 * ## Evaluation Pipeline
 * 1. `${...}` placeholder resolution — Spring [StringValueResolver] (constructor-injected)
 * 2. Literal fast-path — bypasses SpEL when the regex matches (~100ns)
 * 3. **Auto-detection** — template mode when `#{...}` is present, plain SpEL otherwise
 *    - Plain: `name = "#region"` — entire expression is SpEL
 *    - Template: `name = "prefix-#{#region}-suffix"` — literal + SpEL mix (`TemplateParserContext`)
 * 4. Caffeine cache hit ~200ns; parser invoked on miss
 * 5. [SimpleEvaluationContext] evaluation — only read-only property access allowed by default
 *
 * ## Template Mode (#82)
 * Auto-detects `#{...}` patterns using `TemplateParserContext.DEFAULT_TEMPLATE_PARSER_CONTEXT`.
 * - ✅ `name = "daily-#{#region}"` → `"daily-KR"` (template)
 * - ✅ `name = "#region"` → `"KR"` (plain SpEL)
 * - ✅ `name = "my-lock"` → `"my-lock"` (literal fast-path)
 *
 * ## [Step 3-P-Sec-1][R-32] Security Defaults
 * `withMethodResolvers()` is removed by default — blocks arbitrary method calls such as `#root.target.shutdown()`.
 * Enabled only when [allowMethodInvocation] = `true`.
 *
 * ## Cache
 * Caffeine `maximumSize(1024) + expireAfterAccess(1h)` — DoS defence.
 * Template expressions are distinguished in the cache key with a `"T:"` prefix.
 *
 * @param embeddedValueResolver Spring `${...}` placeholder resolver
 * @param allowMethodInvocation When `true`, enables `withMethodResolvers()` and exposes `#root.target`. default `false`
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
     * Evaluates [expression] and returns the string result.
     *
     * Evaluation order: `${...}` resolution → literal fast-path → auto-detection (template/plain) → SpEL evaluation.
     *
     * @return The evaluated lock name
     * @throws IllegalStateException If the SpEL result is `null`
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
     * Pre-parses [expression] at startup — fails fast with a message including the method FQN if the SpEL is invalid.
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

    /** Current cache size. Exposed via health endpoint. */
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
     * SpEL `#root` object — exposes `#root.method`, `#root.methodName`, `#root.args`,
     * and `#root.target` only when [allowMethodInvocation]=true.
     */
    @Suppress("unused")
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
     * Pre-built helpers such as [MethodBasedEvaluationContext] are not compatible with [SimpleEvaluationContext],
     * so this evaluator uses [RootCtx] directly.
     */
    @Suppress("unused")
    private fun unusedReference(): Pair<Class<*>, Class<*>> =
        MethodBasedEvaluationContext::class.java to StandardEvaluationContext::class.java

    companion object: KLogging() {
        /** Static lock name pattern — bypasses SpEL evaluation on match. */
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")

        /** `#{...}` pattern detector — automatically selects template mode (#82). */
        private val TEMPLATE_DETECT = Regex("#\\{.+}")

        /** Spring TemplateParserContext — default `#{` / `}` delimiters. */
        private val TEMPLATE_PARSER_CTX = org.springframework.expression.ParserContext.TEMPLATE_EXPRESSION

        /** Caffeine cache maximum size. */
        const val MAX_CACHE_SIZE: Long = 1024L
    }
}
