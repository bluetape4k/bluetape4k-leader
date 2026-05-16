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
 * Schema initializer utility for Exposed JDBC leader election tables.
 *
 * Runs [SchemaUtils.createMissingTablesAndColumns] exactly once per `Database` URL.
 * On initialization failure the guard key is removed so the next call can retry.
 */
internal object ExposedJdbcSchemaInitializer : KLogging() {

    private val initializedDbs = ConcurrentHashMap<String, Boolean>()
    private val initLock = ReentrantLock()

    /**
     * Creates leader election tables in [db] if they do not exist. Runs at most once per database URL.
     *
     * When schema creation fails the guard key is not recorded, so the next call will retry.
     * Logs context on failure and propagates the original exception.
     *
     * ## Recommended configuration for H2
     *
     * When using H2, set `MODE=MySQL` or `MODE=PostgreSQL` in the JDBC URL.
     * In the default mode, some column type or syntax differences may cause DDL or DML
     * to behave unexpectedly.
     *
     * Recommended URL examples:
     * - H2 in-memory:  `jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1`
     * - H2 file:       `jdbc:h2:file:./data/leader;MODE=MySQL`
     * - PostgreSQL:    `jdbc:postgresql://host:5432/db`
     * - MySQL:         `jdbc:mysql://host:3306/db`
     *
     * @throws Exception on DB error during schema creation (retry is allowed)
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
     * Masks the userinfo (especially the password) in a JDBC URL with `***`.
     *
     * Strips the `jdbc:` prefix, parses the remainder as a [URI], and replaces only the userinfo portion.
     * Returns the original URL on parse failure (best-effort).
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

    /** Resets the initialization state for a specific database in tests. */
    internal fun resetFor(db: Database) {
        initializedDbs.remove(db.url)
    }
}

/**
 * Validates `lockName`.
 *
 * Applies the leader-core common rules (allowed characters, first character, 255-character limit)
 * via [validateLockName].
 *
 * @throws IllegalArgumentException when `lockName` is invalid
 */
internal fun validateExposedLockName(lockName: String) {
    validateLockName(lockName)
}
