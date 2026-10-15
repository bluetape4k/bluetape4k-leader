package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * `MongoHistoryConfig`는 history collection 이름과 `startedAt` TTL index 보존 정책을 정하는 직렬화 가능한 설정 모델입니다.
 *
 * @property collectionName history 문서를 저장할 collection 이름입니다. 기본값은 `bluetape4k_leader_history`이고, 공백·`system.` 접두사·`$`·NUL과 120자 초과를 허용하지 않습니다.
 * @property ttlDays `startedAt` TTL index의 보존 일수입니다. 기본값은 90일이며 `0`이면 TTL index를 만들지 않습니다.
 */
data class MongoHistoryConfig(
    val collectionName: String = DEFAULT_COLLECTION_NAME,
    val ttlDays: Long = DEFAULT_TTL_DAYS,
): Serializable {

    init {
        validateMongoHistoryCollectionName(collectionName)
        require(ttlDays >= 0) { "ttlDays must be >= 0. Use 0 to disable TTL index. ttlDays=$ttlDays" }
    }

    companion object: KLogging() {
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
