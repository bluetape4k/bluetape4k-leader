package io.bluetape4k.leader.spring.aop.spel

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.expression.spel.SpelEvaluationException

/**
 * [SpelExpressionEvaluator] (T5.1 + T5.9a):
 * - literal fast-path
 * - plain SpEL (`#region`, `'process-' + #region`, `#user.tenantId`)
 * - `${...}` placeholder 해석 → literal 경로 / SpEL 경로
 * - SpEL null 결과 → IllegalStateException (메서드 FQN 포함)
 * - SpEL pre-parse 실패 (메서드 FQN 포함)
 * - [Step 3-P-Sec-1][R-32] 메서드 호출 차단 default + opt-in
 * - 시스템 타입 / Spring 빈 참조 차단
 */
class SpelExpressionEvaluatorTest {

    companion object: KLogging() {
        private const val SAMPLE_TENANT = "tenant-x"
    }

    private val resolver: (String) -> String? = { it }  // identity placeholder resolver

    private fun newEvaluator(allowMethodInvocation: Boolean = false): SpelExpressionEvaluator =
        SpelExpressionEvaluator(
            embeddedValueResolver = { v -> resolver(v) },
            allowMethodInvocation = allowMethodInvocation,
        )

    private fun method(name: String): java.lang.reflect.Method =
        SampleService::class.java.getDeclaredMethod(name, String::class.java)

    private class SampleService {
        fun process(region: String): String = region
        fun rebuild(user: User): String = user.tenantId
    }

    private data class User(val tenantId: String)

    @Test
    fun `literal fast-path - 정적 이름은 SpEL 우회`() {
        val sut = newEvaluator()
        sut.evaluate("daily-job", method("process"), arrayOf("EU"), SampleService()) shouldBeEqualTo "daily-job"
    }

    @Test
    fun `plain SpEL - argName 변수 평가`() {
        val sut = newEvaluator()
        sut.evaluate("#region", method("process"), arrayOf("EU"), SampleService()) shouldBeEqualTo "EU"
    }

    @Test
    fun `plain SpEL - 리터럴 prefix 따옴표 + argName 결합`() {
        val sut = newEvaluator()
        sut.evaluate("'process-' + #region", method("process"), arrayOf("EU"), SampleService()) shouldBeEqualTo "process-EU"
    }

    @Test
    fun `plain SpEL - property access`() {
        val sut = newEvaluator()
        val user = User(SAMPLE_TENANT)
        val rebuildMethod = SampleService::class.java.getDeclaredMethod("rebuild", User::class.java)
        sut.evaluate("#user.tenantId", rebuildMethod, arrayOf<Any?>(user), SampleService()) shouldBeEqualTo SAMPLE_TENANT
    }

    @Test
    fun `plain SpEL - aN 인덱스 폴백`() {
        val sut = newEvaluator()
        sut.evaluate("#a0", method("process"), arrayOf("EU"), SampleService()) shouldBeEqualTo "EU"
    }

    @Test
    fun `plain SpEL - pN 인덱스 폴백`() {
        val sut = newEvaluator()
        sut.evaluate("#p0", method("process"), arrayOf("EU"), SampleService()) shouldBeEqualTo "EU"
    }

    @Test
    fun `보안 - T(System) 타입 참조 차단`() {
        val sut = newEvaluator()
        assertThrows<SpelEvaluationException> {
            sut.evaluate("T(java.lang.System).getProperty('user.home')", method("process"), arrayOf("X"), SampleService())
        }
    }

    @Test
    fun `보안 default - 메서드 호출 차단 (R-32)`() {
        val sut = newEvaluator(allowMethodInvocation = false)
        assertThrows<SpelEvaluationException> {
            sut.evaluate("#region.toUpperCase()", method("process"), arrayOf("eu"), SampleService())
        }
    }

    @Test
    fun `보안 opt-in - allowMethodInvocation true 시 메서드 호출 허용`() {
        val sut = newEvaluator(allowMethodInvocation = true)
        sut.evaluate("#region.toUpperCase()", method("process"), arrayOf("eu"), SampleService()) shouldBeEqualTo "EU"
    }

    @Test
    fun `pre-parse 실패 - 메서드 FQN 포함 메시지`() {
        val sut = newEvaluator()
        val ex = runCatching { sut.preParse("'process-#region", method("process")) }.exceptionOrNull()
        check(ex != null) { "expected throw" }
        check(ex.message!!.contains("SampleService")) { "message should include FQN: ${ex.message}" }
        check(ex.message!!.contains("process")) { "message should include method name: ${ex.message}" }
    }

    @Test
    fun `pre-parse - literal 은 통과 (cache 미적재)`() {
        val sut = newEvaluator()
        sut.preParse("daily-job", method("process"))
        sut.cacheSize() shouldBeEqualTo 0L
    }

    @Test
    fun `pre-parse - SpEL 표현식 cache 적재`() {
        val sut = newEvaluator()
        sut.preParse("#region", method("process"))
        sut.preParse("'x-' + #region", method("process"))
        check(sut.cacheSize() >= 2L) { "cacheSize should be >= 2, got ${sut.cacheSize()}" }
    }

    // ── #97: 누락 경로 테스트 ──

    @Test
    fun `SpEL null 결과 - IllegalStateException 메서드 FQN 포함`() {
        val sut = newEvaluator()
        // #a0 = null → getValue(String::class.java) = null → error() 분기
        val ex = assertThrows<IllegalStateException> {
            sut.evaluate("#a0", method("process"), arrayOf<Any?>(null), SampleService())
        }
        ex.message!! shouldContain "SampleService"
        ex.message!! shouldContain "process"
    }

    @Test
    fun `placeholder - 해석 후 literal 경로 진입`() {
        // "\${app.lock.name}" → "daily-job" → literal fast-path (SpEL 우회)
        val sut = SpelExpressionEvaluator(
            embeddedValueResolver = { expr -> if (expr == "\${app.lock.name}") "daily-job" else expr }
        )
        sut.evaluate("\${app.lock.name}", method("process"), arrayOf("X"), SampleService()) shouldBeEqualTo "daily-job"
        sut.cacheSize() shouldBeEqualTo 0L  // literal 이므로 cache 미적재
    }

    @Test
    fun `placeholder - 해석 후 SpEL 경로 진입`() {
        // "\${lock.prefix} + #region" → "'batch-' + #region" → SpEL → "batch-EU"
        val sut = SpelExpressionEvaluator(
            embeddedValueResolver = { expr -> expr.replace("\${lock.prefix}", "'batch-'") }
        )
        sut.evaluate(
            "\${lock.prefix} + #region",
            method("process"),
            arrayOf("EU"),
            SampleService(),
        ) shouldBeEqualTo "batch-EU"
    }
}
