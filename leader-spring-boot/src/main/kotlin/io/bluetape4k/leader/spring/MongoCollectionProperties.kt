package io.bluetape4k.leader.spring

/**
 * MongoDB leader backend가 단일 leader와 group leader 기록을 저장할 collection 이름입니다.
 *
 * @property singleCollection 단일 leader lock 문서를 저장할 collection 이름입니다. 기본값은 `leader_election`입니다.
 * @property groupCollection group leader lock 문서를 저장할 collection 이름입니다. 기본값은 `leader_group_election`입니다.
 */
data class MongoCollectionProperties(
    val singleCollection: String = "leader_election",
    val groupCollection: String = "leader_group_election",
)
