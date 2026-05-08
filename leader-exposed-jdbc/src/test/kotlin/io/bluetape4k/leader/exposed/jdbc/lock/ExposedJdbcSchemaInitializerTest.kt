package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExposedJdbcSchemaInitializerTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    // --- sanitizeUrl ---

    @Test
    fun `sanitizeUrl - jdbc postgres URL 의 password가 마스킹된다`() {
        val url = "jdbc:postgresql://alice:s3cret@db.example.com:5432/leader"
        val sanitized = ExposedJdbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldNotContain "s3cret"
        sanitized shouldContain "***"
        sanitized shouldContain "jdbc:"
        sanitized shouldContain "db.example.com"
        sanitized shouldContain "leader"
    }

    @Test
    fun `sanitizeUrl - jdbc mysql URL 의 password가 마스킹된다`() {
        val url = "jdbc:mysql://root:topsecret@mysql.host:3306/leader?useSSL=false"
        val sanitized = ExposedJdbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldNotContain "topsecret"
        sanitized shouldContain "***"
    }

    @Test
    fun `sanitizeUrl - userinfo 없는 URL은 그대로 반환된다`() {
        val url = "jdbc:postgresql://db.example.com:5432/leader"
        val sanitized = ExposedJdbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldBeEqualTo url
    }

    @Test
    fun `sanitizeUrl - 잘못된 형식의 URL은 원본 그대로 반환된다`() {
        val url = "not::a::valid::url"
        val sanitized = ExposedJdbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldBeEqualTo url
    }

    @Test
    fun `sanitizeUrl - 빈 userinfo 는 원본 그대로 반환된다`() {
        val url = "jdbc:postgresql://@db.example.com:5432/leader"
        val sanitized = ExposedJdbcSchemaInitializer.sanitizeUrl(url)

        sanitized shouldBeEqualTo url
    }

    // --- ensureSchema 동시성 ---

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `ensureSchema - 다중 스레드 동시 호출에도 예외 없이 1회만 초기화된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        ExposedJdbcSchemaInitializer.resetFor(db)

        val threadCount = 16
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val errorCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            repeat(threadCount) {
                executor.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        ExposedJdbcSchemaInitializer.ensureSchema(db)
                    } catch (e: Throwable) {
                        log.warn(e) { "ensureSchema 예외 발생" }
                        errorCount.incrementAndGet()
                    }
                }
            }
            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        errorCount.get() shouldBeEqualTo 0
    }
}
