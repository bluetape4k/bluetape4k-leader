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
 * `ExposedR2dbcSchemaInitializer`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object ExposedR2dbcSchemaInitializer : KLoggingChannel() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initMutex = Mutex()

    /**
     * `ensureSchema` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun ensureSchema(db: R2dbcDatabase) {
        val dbKey = db.url
        if (initializedDbs.containsKey(dbKey)) return
        initMutex.withLock {
            if (initializedDbs.containsKey(dbKey)) return
            try {
                suspendTransaction(db) {
                    SchemaUtils.create(*ExposedLeaderSchema.allTables)
                    SchemaUtils
                        .addMissingColumnsStatements(*ExposedLeaderSchema.allTables)
                        .forEach { sql -> exec(sql) }
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
     * `sanitizeUrl` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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

    /**
     * `resetFor` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    internal fun resetFor(db: R2dbcDatabase) {
        initializedDbs.remove(db.url)
    }
}

/**
 * `validateExposedR2dbcLockName` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
internal fun validateExposedR2dbcLockName(lockName: String) {
    validateLockName(lockName)
}
