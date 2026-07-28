package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElector
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import java.io.Serializable
import java.time.Instant

/**
 * `LeaderElectionStatusEndpoint`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property leaderElector Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property registry Spring Boot integration 계약에서 사용하는 속성입니다.
 */
@Endpoint(id = "leaderElection")
class LeaderElectionStatusEndpoint(
    private val leaderElector: LeaderElector,
    private val registry: LeaderElectionStatusRegistry,
) {

    /**
     * `leaderElectionStatus` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @ReadOperation
    fun leaderElectionStatus(): LeaderElectionStatusResponse =
        LeaderElectionStatusResponse(
            locks = registry.snapshot().map { lockName ->
                val state = leaderElector.state(lockName)
                LeaderElectionLockStatus(
                    name = lockName,
                    status = state.status.name,
                    leaderId = state.leader?.auditLeaderId,
                    leaseExpiry = state.leader?.leaseUntil,
                )
            }
        )
}

/**
 * `LeaderElectionStatusResponse`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property locks Spring Boot integration 계약에서 `locks` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderElectionStatusResponse(
    val locks: List<LeaderElectionLockStatus>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * `LeaderElectionLockStatus`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property name Spring Boot integration 계약에서 `name` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status Spring Boot integration 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaderId Spring Boot integration 계약에서 `leaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseExpiry Spring Boot integration 계약에서 `leaseExpiry` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderElectionLockStatus(
    val name: String,
    val status: String,
    val leaderId: String?,
    val leaseExpiry: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
