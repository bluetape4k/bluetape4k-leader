package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderManagementActionRegistry
import io.bluetape4k.leader.LeaderManagementAction
import io.bluetape4k.leader.LeaderManagementActionOutcome
import io.bluetape4k.leader.LeaderManagementActionResult
import io.bluetape4k.leader.LeaderManagementActionSurface
import io.bluetape4k.leader.LeaderManagementHttpContract
import io.bluetape4k.leader.isManagementActionLockName
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint
import java.io.Serializable

/**
 * 등록된 lease에 대한 명시적 release write action을 HTTP에만 노출합니다.
 *
 * 이 endpoint는 `@Endpoint`를 사용하지 않으므로 JMX status endpoint와 분리됩니다.
 * response에는 운영자가 재시도 정책을 판단하는 데 필요한 세 필드만 포함합니다.
 */
@WebEndpoint(id = "leaderElectionActions")
class LeaderElectionActionWebEndpoint(
    private val registry: LeaderManagementActionRegistry,
) {

    /** lock selector를 검증하고 공통 HTTP status 계약으로 release 결과를 감쌉니다. */
    @WriteOperation
    fun release(@Selector lockName: String): WebEndpointResponse<LeaderManagementActionHttpResponse> {
        val result = if (isManagementActionLockName(lockName)) {
            registry.release(lockName, LeaderManagementActionSurface.SPRING)
        } else {
            LeaderManagementActionResult(
                action = LeaderManagementAction.RELEASE,
                outcome = LeaderManagementActionOutcome.INVALID_LOCK_NAME,
                mutationAttempted = false,
            )
        }
        return WebEndpointResponse(
            LeaderManagementActionHttpResponse.from(result),
            LeaderManagementHttpContract.statusCode(result.outcome),
        )
    }

}

/**
 * Spring action response의 공개 allow-list입니다. lock, actor, token, 예외 원문은
 * 절대 직렬화하지 않습니다.
 */
data class LeaderManagementActionHttpResponse(
    val action: String,
    val outcome: String,
    val mutationAttempted: Boolean,
) : Serializable {

    companion object {
        fun from(result: LeaderManagementActionResult): LeaderManagementActionHttpResponse =
            LeaderManagementActionHttpResponse(
                action = result.action.name,
                outcome = result.outcome.name,
                mutationAttempted = result.mutationAttempted,
            )

        private const val serialVersionUID = 1L
    }
}
