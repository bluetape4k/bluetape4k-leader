package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.leader.exposed.ExposedLeaderSchema
import io.bluetape4k.leader.exposed.internal.redactDatabaseUrlForLog
import io.bluetape4k.leader.identity.LeaderInternalApi
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `ExposedJdbcSchemaInitializer`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object ExposedJdbcSchemaInitializer : KLogging() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initLock = ReentrantLock()

    /**
     * `ensureSchema` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun ensureSchema(db: Database) {
        val dbKey = db.url
        if (initializedDbs.containsKey(dbKey)) return
        initLock.withLock {
            if (initializedDbs.containsKey(dbKey)) return
            try {
                transaction(db) {
                    SchemaUtils.create(*ExposedLeaderSchema.allTables)
                    SchemaUtils
                        .addMissingColumnsStatements(*ExposedLeaderSchema.allTables)
                        .forEach { sql -> exec(sql) }
                }
            } catch (e: Throwable) {
                logInitializationFailure(dbKey, e)
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
    @OptIn(LeaderInternalApi::class)
    internal fun sanitizeUrl(url: String): String =
        redactDatabaseUrlForLog(url)

    internal fun logInitializationFailure(url: String, error: Throwable) {
        val errorType = error::class.qualifiedName ?: error::class.simpleName ?: "unknown"
        log.warn {
            "리더 선출 스키마 초기화 실패 (다음 호출 시 재시도): ${sanitizeUrl(url)}, errorType=$errorType"
        }
    }

    /**
     * `resetFor` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    internal fun resetFor(db: Database) {
        initializedDbs.remove(db.url)
    }
}

/**
 * `validateExposedLockName` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
internal fun validateExposedLockName(lockName: String) {
    validateLockName(lockName)
}
