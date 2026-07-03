package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * Configuration for MongoDB-backed leader-lock history storage.
 *
 * ## Properties
 * - [collectionName]: MongoDB collection name for history records. Defaults to
 *   [DEFAULT_COLLECTION_NAME].
 * - [ttlDays]: TTL index expiry in days. A value of `0` disables the TTL index;
 *   the `leader.history.mongodb.ttl.disabled` gauge will read `1.0` in that case.
 *
 * ## Behavior / Contract
 * - TTL index is the primary retention mechanism in MongoDB; `deleteOlderThan()`
 *   is a supplementary immediate-purge helper.
 * - When [ttlDays] is `0`, data accumulates indefinitely unless the caller invokes
 *   `deleteOlderThan()` explicitly.
 */
data class MongoHistoryConfig(
    val collectionName: String = DEFAULT_COLLECTION_NAME,
    val ttlDays: Long = DEFAULT_TTL_DAYS,
) : Serializable {

    init {
        validateMongoHistoryCollectionName(collectionName)
        require(ttlDays >= 0) { "ttlDays must be >= 0. Use 0 to disable TTL index. ttlDays=$ttlDays" }
    }

    companion object : KLogging() {
        private const val serialVersionUID = 1L

        const val DEFAULT_COLLECTION_NAME = "bluetape4k_leader_history"
        const val DEFAULT_TTL_DAYS = 90L
    }
}

private fun validateMongoHistoryCollectionName(collectionName: String) {
    require(collectionName.isNotBlank()) { "collectionName must not be blank" }
    require(collectionName.length <= 120) { "collectionName.length must be <= 120: ${collectionName.length}" }
    require(!collectionName.startsWith("system.")) {
        "collectionName must not use MongoDB reserved system namespace: $collectionName"
    }
    require(collectionName.none { it == '\u0000' || it == '$' }) {
        "collectionName must not contain MongoDB reserved null or '$' characters: $collectionName"
    }
}
