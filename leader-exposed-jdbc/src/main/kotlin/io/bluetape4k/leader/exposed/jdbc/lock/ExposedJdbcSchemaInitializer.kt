package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.leader.exposed.ExposedLeaderSchema
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Exposed JDBC 리더 선출 테이블 스키마 초기화 유틸리티.
 *
 * `Database` URL 기준으로 최초 1회만 [SchemaUtils.createMissingTablesAndColumns]를 실행합니다.
 * 초기화 실패 시 guard key를 제거하여 다음 호출 시 재시도할 수 있습니다.
 */
internal object ExposedJdbcSchemaInitializer : KLogging() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initLock = ReentrantLock()

    /**
     * [db]에 리더 선출 테이블이 없으면 생성합니다. 동일 DB URL에 대해 최초 1회만 실행됩니다.
     *
     * 스키마 생성 실패 시 guard key를 설정하지 않으므로 다음 호출 시 재시도됩니다.
     * 실패 시 컨텍스트 로그를 남기고 원본 예외를 그대로 전파합니다.
     *
     * ## H2 사용 시 권장 설정
     *
     * H2를 사용하는 경우 JDBC URL에 `MODE=MySQL` 또는 `MODE=PostgreSQL`을 설정하는 것을
     * 권장합니다. Default 모드에서는 일부 컬럼 타입/syntax 차이로 DDL 또는 DML이
     * 예상과 다르게 동작할 수 있습니다.
     *
     * 권장 URL 예시:
     * - H2 in-memory:  `jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1`
     * - H2 파일:       `jdbc:h2:file:./data/leader;MODE=MySQL`
     * - PostgreSQL:    `jdbc:postgresql://host:5432/db`
     * - MySQL:         `jdbc:mysql://host:3306/db`
     *
     * @throws Exception 스키마 생성 중 DB 오류 발생 시 (재시도 허용)
     */
    fun ensureSchema(db: Database) {
        val dbKey = db.url
        if (initializedDbs.containsKey(dbKey)) return
        initLock.withLock {
            if (initializedDbs.containsKey(dbKey)) return
            try {
                transaction(db) {
                    SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
                }
            } catch (e: Throwable) {
                log.warn(e) { "리더 선출 스키마 초기화 실패 (다음 호출 시 재시도): ${sanitizeUrl(dbKey)}" }
                throw e
            }
            initializedDbs[dbKey] = true
            log.debug { "리더 선출 스키마 초기화 완료: ${sanitizeUrl(dbKey)}" }
        }
    }

    /**
     * JDBC URL 내 userinfo(특히 password)를 `***`로 마스킹합니다.
     *
     * `jdbc:` 접두사를 제거한 후 [URI]로 파싱하여 userinfo 부분만 치환합니다.
     * 파싱 실패 시 원본 URL을 반환합니다 (best-effort).
     */
    internal fun sanitizeUrl(url: String): String {
        // "jdbc:postgresql://user:pw@host/db" → URI는 opaque로 파싱하므로 rawUserInfo == null.
        // 접두사 제거 후 hierarchical URI로 재파싱하여 userinfo 추출.
        val (prefix, rest) = if (url.startsWith("jdbc:", ignoreCase = true)) {
            "jdbc:" to url.substring(5)
        } else {
            "" to url
        }
        return try {
            val uri = URI(rest)
            val rawUserInfo = uri.rawUserInfo
            if (rawUserInfo.isNullOrEmpty()) return url

            val sanitized = URI(
                uri.scheme,
                "***",
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
            prefix + sanitized
        } catch (_: URISyntaxException) {
            url
        } catch (_: IllegalArgumentException) {
            url
        }
    }

    /** 테스트에서 특정 DB의 초기화 상태를 초기화합니다. */
    internal fun resetFor(db: Database) {
        initializedDbs.remove(db.url)
    }
}

/**
 * `lockName`의 유효성을 검증합니다.
 *
 * [validateLockName]으로 leader-core 공통 규칙(허용 문자, 첫 글자, 255자 제한)을 적용합니다.
 *
 * @throws IllegalArgumentException 유효하지 않은 lockName인 경우
 */
internal fun validateExposedLockName(lockName: String) {
    validateLockName(lockName)
}
