package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.leader.exposed.ExposedLeaderSchema
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposed R2DBC 리더 선출 테이블 스키마 초기화 유틸리티.
 *
 * R2DBC URL 기준으로 최초 1회만 [SchemaUtils.createMissingTablesAndColumns]를 실행합니다.
 * Coroutine-safe double-check: [Mutex] + [ConcurrentHashMap] 조합으로 구현합니다.
 * 초기화 실패 시 guard key를 제거하여 다음 호출 시 재시도할 수 있습니다.
 */
internal object ExposedR2dbcSchemaInitializer : KLoggingChannel() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initMutex = Mutex()

    /**
     * [db]에 리더 선출 테이블이 없으면 생성합니다. 동일 DB URL에 대해 최초 1회만 실행됩니다.
     *
     * 스키마 생성 실패 시 guard key를 설정하지 않으므로 다음 호출 시 재시도됩니다.
     * 실패 시 컨텍스트 로그를 남기고 원본 예외를 그대로 전파합니다.
     *
     * ## H2 사용 시 필수 설정
     *
     * H2 in-memory 데이터베이스를 사용하는 경우 R2DBC URL에 `MODE=MySQL` 또는
     * `MODE=PostgreSQL`을 **반드시** 설정해야 합니다. Default 모드에서는 `insertIgnore`가
     * 지원되지 않아 락 획득이 정상적으로 동작하지 않습니다.
     *
     * 권장 URL 예시:
     * - H2 in-memory:  `r2dbc:h2:mem:///test;MODE=MySQL;DB_CLOSE_DELAY=-1`
     * - H2 파일:       `r2dbc:h2:file:///./data/leader;MODE=MySQL`
     * - PostgreSQL:    `r2dbc:postgresql://host:5432/db`
     * - MySQL:         `r2dbc:mysql://host:3306/db`
     *
     * @throws Exception 스키마 생성 중 DB 오류 발생 시 (재시도 허용)
     */
    suspend fun ensureSchema(db: R2dbcDatabase) {
        val dbKey = db.url
        if (initializedDbs.containsKey(dbKey)) return
        initMutex.withLock {
            if (initializedDbs.containsKey(dbKey)) return
            try {
                suspendTransaction(db) {
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
     * R2DBC URL 내 userinfo(특히 password)를 `***`로 마스킹합니다.
     *
     * `r2dbc:` 접두사를 제거한 뒤 [URI]로 파싱하여 새 [URI]를 재구성하므로,
     * userinfo는 `***`로, 나머지 컴포넌트(scheme/host/port/path/query/fragment)는 그대로 보존됩니다.
     * 파싱 실패([java.net.URISyntaxException], [IllegalArgumentException] 등)시 원본 URL을 반환합니다 (best-effort).
     */
    internal fun sanitizeUrl(url: String): String {
        val (prefix, rest) = when {
            url.startsWith("r2dbc:", ignoreCase = true) -> "r2dbc:" to url.substring(6)
            else -> "" to url
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
    internal fun resetFor(db: R2dbcDatabase) {
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
internal fun validateExposedR2dbcLockName(lockName: String) {
    validateLockName(lockName)
}
