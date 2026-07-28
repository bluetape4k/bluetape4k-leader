package io.bluetape4k.leader.spring

/**
 * `MongoCollectionProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property singleCollection Spring Boot integration 계약에서 `singleCollection` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property groupCollection Spring Boot integration 계약에서 `groupCollection` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class MongoCollectionProperties(
    val singleCollection: String = "leader_election",
    val groupCollection: String = "leader_group_election",
)
