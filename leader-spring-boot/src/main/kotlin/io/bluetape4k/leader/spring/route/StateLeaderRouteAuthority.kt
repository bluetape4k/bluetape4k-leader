package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import java.time.Clock
import java.util.concurrent.CancellationException

/** `LeaderElector.state`의 audit leader ID와 요청 slot을 비교해 route authority를 판단합니다.
 *
 * @property elector audit leader state를 제공해야 하는 elector입니다.
 */
class StateLeaderRouteAuthority(
    private val elector: LeaderElector,
): LeaderRouteAuthority {

    companion object: KLogging()

    init {
        if (!elector.supportsAuditLeaderState) {
            throw LeaderRouteGuardConfigurationException(
                LeaderRouteGuardConfigurationException.ELECTOR_STATE_UNSUPPORTED,
                "StateLeaderRouteAuthority requires a LeaderElector that exposes audit leader identity",
            )
        }
    }

    override fun evaluate(slot: LeaderSlot): LeaderRouteDecision =
        evaluateSnapshot(slot, Clock.systemUTC()).decision

    internal fun evaluateSnapshot(slot: LeaderSlot, clock: Clock): LeaderRouteEvaluation =
        try {
            val state = elector.state(slot.lockName)
            val evaluatedAt = clock.instant()
            LeaderRouteEvaluation(
                decision = if (state.isOccupied && state.leader?.auditLeaderId == slot.leaderId) {
                    LeaderRouteDecision.Allowed
                } else {
                    LeaderRouteDecision.NotLeader
                },
                leaderState = state,
                evaluatedAt = evaluatedAt,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            LeaderRouteEvaluation(LeaderRouteDecision.Unavailable, null, clock.instant())
        }
}
