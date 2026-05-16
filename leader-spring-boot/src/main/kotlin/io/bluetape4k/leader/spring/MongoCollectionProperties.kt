package io.bluetape4k.leader.spring

/**
 * Collection name properties for the MongoDB backend.
 *
 * Separates the collection names used by single and group elections. Even when only a `MongoDatabase`
 * bean is present in the application, the correct collection can be selected safely through these properties.
 *
 * @property singleCollection Collection for single-leader election. Default `leader_election`
 * @property groupCollection Collection for multi-leader group election. Default `leader_group_election`
 */
data class MongoCollectionProperties(
    val singleCollection: String = "leader_election",
    val groupCollection: String = "leader_group_election",
)
