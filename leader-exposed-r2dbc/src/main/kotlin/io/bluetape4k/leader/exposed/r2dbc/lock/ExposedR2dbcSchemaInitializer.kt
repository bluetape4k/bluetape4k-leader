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
 * Utility for initializing the Exposed R2DBC leader election table schema.
 *
 * Runs [SchemaUtils.createMissingTablesAndColumns] only once per R2DBC URL.
 * Coroutine-safe double-check implemented with [Mutex] + [ConcurrentHashMap].
 * On initialization failure, the guard key is removed to allow retry on the next call.
 */
internal object ExposedR2dbcSchemaInitializer : KLoggingChannel() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initMutex = Mutex()

    /**
     * Creates leader election tables in [db] if they do not exist. Runs only once per DB URL.
     *
     * On schema creation failure, the guard key is not set, so the next call will retry.
     * Logs context on failure and propagates the original exception as-is.
     *
     * ## Required configuration when using H2
     *
     * When using an H2 in-memory database, `MODE=MySQL` or `MODE=PostgreSQL` **must** be set
     * in the R2DBC URL. In default mode, `insertIgnore` is not supported and lock acquisition
     * will not work correctly.
     *
     * Recommended URL examples:
     * - H2 in-memory:  `r2dbc:h2:mem:///test;MODE=MySQL;DB_CLOSE_DELAY=-1`
     * - H2 file:       `r2dbc:h2:file:///./data/leader;MODE=MySQL`
     * - PostgreSQL:    `r2dbc:postgresql://host:5432/db`
     * - MySQL:         `r2dbc:mysql://host:3306/db`
     *
     * @throws Exception when a DB error occurs during schema creation (retry allowed)
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
     * Masks the userinfo (especially the password) in a R2DBC URL with `***`.
     *
     * Strips the `r2dbc:` prefix, parses the remainder as a [URI], and reconstructs a new [URI],
     * replacing userinfo with `***` while preserving all other components
     * (scheme/host/port/path/query/fragment).
     * Returns the original URL on parse failure ([java.net.URISyntaxException], [IllegalArgumentException], etc.)
     * on a best-effort basis.
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

    /** Resets the initialization state for a specific DB in tests. */
    internal fun resetFor(db: R2dbcDatabase) {
        initializedDbs.remove(db.url)
    }
}

/**
 * Validates `lockName`.
 *
 * Applies the leader-core common rules (allowed characters, first character, 255-character limit)
 * via [validateLockName].
 *
 * @throws IllegalArgumentException if `lockName` is invalid
 */
internal fun validateExposedR2dbcLockName(lockName: String) {
    validateLockName(lockName)
}
