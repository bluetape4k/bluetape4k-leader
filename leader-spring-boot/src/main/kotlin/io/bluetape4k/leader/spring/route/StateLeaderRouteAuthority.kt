package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderSlot
import java.util.concurrent.CancellationException

/**
 * `StateLeaderRouteAuthority`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property elector Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class StateLeaderRouteAuthority(
    private val elector: LeaderElector,
) : LeaderRouteAuthority {

    init {
        if (!elector.supportsAuditLeaderState) {
            throw LeaderRouteGuardConfigurationException(
                LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED,
                "StateLeaderRouteAuthority requires a LeaderElector that exposes audit leader identity",
            )
        }
    }

    override fun evaluate(slot: LeaderSlot): LeaderRouteDecision =
        try {
            val state = elector.state(slot.lockName)
            if (state.isOccupied && state.leader?.auditLeaderId == slot.leaderId) {
                LeaderRouteDecision.Allowed
            } else {
                LeaderRouteDecision.NotLeader
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            LeaderRouteDecision.Unavailable
        }
}
