package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.leader.exposed.ExposedLeaderSchema
import io.bluetape4k.leader.exposed.internal.redactDatabaseUrlForLog
import io.bluetape4k.leader.exposed.jdbc.internal.validateExposedLockName as validateInternalExposedLockName
import io.bluetape4k.leader.identity.LeaderInternalApi
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
 * `ExposedJdbcSchemaInitializer`는 JDBC URL별로 leader 테이블을 한 번 생성하고 누락 열을 보완합니다.
 *
 */
internal object ExposedJdbcSchemaInitializer: KLogging() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initLock = ReentrantLock()

    /**
     * [db] URL별로 한 번만 리더 선출 테이블을 생성하고 누락된 컬럼을 추가합니다.
     * 초기화가 실패하면 성공 표시를 남기지 않아 다음 호출에서 다시 시도합니다.
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
                        .forEach { sql ->
                            exec(sql)
                        }
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
     * 로그에 기록할 DB URL에서 사용자명·비밀번호와 같은 민감한 인증 정보를 제거합니다.
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
     * [db] URL의 초기화 완료 표시를 제거합니다. 테스트나 재초기화 후 다음 호출이 스키마를 다시 확인하게 합니다.
     */
    internal fun resetFor(db: Database) {
        initializedDbs.remove(db.url)
    }
}

/** 1.0.0에서 공개한 JVM facade를 유지하는 호환용 lock-name 검증 함수입니다. */
@Deprecated("use String.validateExposedLockName() instead")
@Suppress("DEPRECATION")
fun validateExposedLockName(lockName: String) {
    validateInternalExposedLockName(lockName)
}
