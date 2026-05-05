package io.bluetape4k.leader.spring

/**
 * MongoDB 백엔드용 컬렉션 이름 속성.
 *
 * Single/Group election이 사용할 컬렉션 이름을 분리합니다. 사용자 앱에 `MongoDatabase` 빈만 존재해도
 * 이 속성으로 컬렉션을 안전하게 선택할 수 있습니다.
 *
 * @property singleCollection 단일 리더 선출 컬렉션. 기본 `leader_election`
 * @property groupCollection 멀티 리더 그룹 컬렉션. 기본 `leader_group_election`
 */
data class MongoCollectionProperties(
    val singleCollection: String = "leader_election",
    val groupCollection: String = "leader_group_election",
)
