package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.leader.exposed.ExposedLeaderSchema
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
     * @throws Exception 스키마 생성 중 DB 오류 발생 시 (재시도 허용)
     */
    fun ensureSchema(db: Database) {
        val dbKey = db.url
        if (initializedDbs.containsKey(dbKey)) return
        initLock.withLock {
            if (initializedDbs.containsKey(dbKey)) return
            transaction(db) {
                SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
            }
            initializedDbs[dbKey] = true
            log.debug { "리더 선출 스키마 초기화 완료: ${sanitizeUrl(dbKey)}" }
        }
    }

    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = URI(url)
            val rawUserInfo = uri.rawUserInfo ?: return url
            if (rawUserInfo.isEmpty()) return url

            URI(
                uri.scheme,
                "***",
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
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
