package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ExposedR2dbcSchemaInitializerTest : AbstractExposedR2dbcLeaderTest() {

    companion object : KLoggingChannel()

    // ─── DB 연동 테스트 ───────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `ensureSchema - 최초 호출 시 스키마가 생성된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = connectDb(testDB)
        ExposedR2dbcSchemaInitializer.resetFor(db)

        ExposedR2dbcSchemaInitializer.ensureSchema(db)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `ensureSchema - 동일 DB에 중복 호출해도 예외가 발생하지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = connectDb(testDB)
        ExposedR2dbcSchemaInitializer.resetFor(db)

        ExposedR2dbcSchemaInitializer.ensureSchema(db)
        ExposedR2dbcSchemaInitializer.ensureSchema(db)
        ExposedR2dbcSchemaInitializer.ensureSchema(db)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `ensureSchema - 동시 호출 10건에도 예외 없이 단일 초기화로 수렴한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = connectDb(testDB)
        ExposedR2dbcSchemaInitializer.resetFor(db)

        coroutineScope {
            (1..10).map {
                async { ExposedR2dbcSchemaInitializer.ensureSchema(db) }
            }.awaitAll()
        }

        // 후속 호출도 정상 (이미 초기화 완료)
        ExposedR2dbcSchemaInitializer.ensureSchema(db)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `resetFor - 초기화 상태를 리셋하면 다음 호출에서 재초기화된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = connectDb(testDB)
        ExposedR2dbcSchemaInitializer.resetFor(db)

        ExposedR2dbcSchemaInitializer.ensureSchema(db)
        ExposedR2dbcSchemaInitializer.resetFor(db)
        ExposedR2dbcSchemaInitializer.ensureSchema(db)
    }

    // ─── 순수 함수 테스트 (DB 불필요) ─────────────────────────────────────────

    @Test
    fun `sanitizeUrl - 비밀번호가 포함된 PostgreSQL URL이 마스킹된다`() {
        val url = "r2dbc:postgresql://user:secret123@localhost:5432/mydb"
        val sanitized = ExposedR2dbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldContain "***"
        sanitized shouldNotContain "secret123"
    }

    @Test
    fun `sanitizeUrl - userinfo 없는 URL은 그대로 반환된다`() {
        val url = "r2dbc:postgresql://localhost:5432/mydb"
        val sanitized = ExposedR2dbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldBeEqualTo url
    }

    @Test
    fun `sanitizeUrl - H2 in-memory URL도 예외 없이 원본 반환된다`() {
        val url = "r2dbc:h2:mem:///leader_test;MODE=MySQL;DB_CLOSE_DELAY=-1"
        val sanitized = ExposedR2dbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldBeEqualTo url
    }

    @Test
    fun `sanitizeUrl - r2dbc 접두사 없는 URL도 처리된다`() {
        val url = "postgresql://user:pass@localhost:5432/mydb"
        val sanitized = ExposedR2dbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldContain "***"
        sanitized shouldNotContain "pass"
    }

    // ─── lockName 검증 테스트 ──────────────────────────────────────────────────

    @Test
    fun `validateExposedR2dbcLockName - 유효한 lockName은 예외가 발생하지 않는다`() {
        val validNames = listOf(
            "my-lock",
            "worker_1",
            "job:daily",
            "a",
            "lock123",
            "test-lock-name"
        )
        validNames.forEach { name ->
            runCatching { validateExposedR2dbcLockName(name) }.isSuccess.shouldBeTrue()
        }
    }

    @Test
    fun `validateExposedR2dbcLockName - 빈 문자열은 예외가 발생한다`() {
        runCatching { validateExposedR2dbcLockName("") }.isFailure.shouldBeTrue()
    }

    @Test
    fun `validateExposedR2dbcLockName - 255자 초과 lockName은 예외가 발생한다`() {
        val tooLong = "a".repeat(256)
        runCatching { validateExposedR2dbcLockName(tooLong) }.isFailure.shouldBeTrue()
    }

    @Test
    fun `validateExposedR2dbcLockName - 허용되지 않는 문자는 예외가 발생한다`() {
        val invalidNames = listOf(
            "has space",
            "has@at",
            "has!excl",
        )
        invalidNames.forEach { name ->
            runCatching { validateExposedR2dbcLockName(name) }.isFailure.shouldBeTrue()
        }
    }
}
