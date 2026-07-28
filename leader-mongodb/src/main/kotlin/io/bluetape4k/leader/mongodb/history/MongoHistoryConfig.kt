package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * `MongoHistoryConfig`는 MongoDB leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property collectionName MongoDB backend 계약에서 `collectionName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ttlDays MongoDB backend 계약에서 `ttlDays` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
