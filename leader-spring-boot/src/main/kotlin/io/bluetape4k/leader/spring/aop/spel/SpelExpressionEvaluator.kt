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
 * Plain SpEL / Template SpEL 평가기 — `@LeaderElection.name` / `@LeaderGroupElection.name` 평가용.
 *
 * ## 평가 파이프라인
 * 1. `${...}` placeholder 해석 — Spring [StringValueResolver] (생성자 주입)
 * 2. literal fast-path — 정규식 매칭 시 SpEL 우회 (~100ns)
 * 3. **자동 감지** — `#{...}` 포함 시 template 모드, 그 외 plain SpEL
 *    - Plain: `name = "#region"` — 전체 SpEL 표현식
 *    - Template: `name = "prefix-#{#region}-suffix"` — 리터럴+SpEL 혼합 (`TemplateParserContext`)
 * 4. Caffeine cache 적중 시 ~200ns, miss 시 parser invoke
 * 5. [SimpleEvaluationContext] 평가 — read-only property access 만 default 허용
 *
 * ## Template 모드 (#82)
 * `#{...}` 패턴 자동 감지 — `TemplateParserContext.DEFAULT_TEMPLATE_PARSER_CONTEXT` 사용.
 * - ✅ `name = "daily-#{#region}"` → `"daily-KR"` (template)
 * - ✅ `name = "#region"` → `"KR"` (plain SpEL)
 * - ✅ `name = "my-lock"` → `"my-lock"` (literal fast-path)
 *
 * ## [Step 3-P-Sec-1][R-32] 보안 default
 * `withMethodResolvers()` 기본 제거 — `#root.target.shutdown()` 같은 임의 메서드 호출 차단.
 * [allowMethodInvocation] = `true` 시에만 활성화.
 *
 * ## 캐시
 * Caffeine `maximumSize(1024) + expireAfterAccess(1h)` — DoS 방어.
 * template 표현식은 캐시 키 앞에 `"T:"` 접두사로 구분.
 *
 * @param embeddedValueResolver Spring `${...}` placeholder 해석기
 * @param allowMethodInvocation `true` 시 `withMethodResolvers()` + `#root.target` 노출. default `false`
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
     * [expression] 을 평가하여 문자열 결과 반환.
     *
     * 평가 순서: `${...}` 해석 → literal fast-path → 자동 감지(template/plain) → SpEL 평가.
     *
     * @return 평가된 락 이름
     * @throws IllegalStateException SpEL 결과가 `null` 인 경우
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
     * [expression] 을 startup 시점에 pre-parse — 잘못된 SpEL 을 메서드 FQN 포함 메시지로 fail-fast.
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

    /** 현재 캐시 크기. Health endpoint 노출용. */
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
     * SpEL `#root` 객체 — `#root.method`, `#root.methodName`, `#root.args`,
     * 그리고 [allowMethodInvocation]=true 일 때만 `#root.target`.
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
     * Pre-built [MethodBasedEvaluationContext] 같은 헬퍼는 [SimpleEvaluationContext] 와 호환되지 않으므로
     * 본 evaluator 는 직접 RootCtx 를 사용한다.
     */
    @Suppress("unused")
    private fun unusedReference(): Pair<Class<*>, Class<*>> =
        MethodBasedEvaluationContext::class.java to StandardEvaluationContext::class.java

    companion object: KLogging() {
        /** 정적 lock name 정규식 — 매칭 시 SpEL 평가 우회. */
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")

        /** `#{...}` 패턴 감지 — template 모드 자동 선택용 (#82). */
        private val TEMPLATE_DETECT = Regex("#\\{.+}")

        /** Spring TemplateParserContext — 기본 `#{` / `}` 구분자. */
        private val TEMPLATE_PARSER_CTX = org.springframework.expression.ParserContext.TEMPLATE_EXPRESSION

        /** Caffeine cache 상한. */
        const val MAX_CACHE_SIZE: Long = 1024L
    }
}
