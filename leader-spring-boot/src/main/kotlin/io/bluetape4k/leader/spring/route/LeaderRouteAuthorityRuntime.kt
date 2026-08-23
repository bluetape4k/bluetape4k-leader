package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot
import java.time.Clock
import java.util.concurrent.CancellationException

/**
 * `LeaderRouteAuthorityRuntime`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property authority Spring Boot integration 계약에서 사용하는 속성입니다.
 */
internal class LeaderRouteAuthorityRuntime(
    internal val authority: LeaderRouteAuthority,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Preserves the constructor descriptor published before redirect freshness support. */
    internal constructor(authority: LeaderRouteAuthority) : this(authority, Clock.systemUTC())

    fun evaluate(slot: LeaderSlot): LeaderRouteDecision = evaluateSnapshot(slot).decision

    internal fun evaluateSnapshot(slot: LeaderSlot): LeaderRouteEvaluation {
        return try {
            when (val routeAuthority = authority) {
                is StateLeaderRouteAuthority -> routeAuthority.evaluateSnapshot(slot, clock)
                else -> evaluateCustom(routeAuthority, slot)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            LeaderRouteEvaluation(LeaderRouteDecision.Unavailable, null, clock.instant())
        }
    }

    private fun evaluateCustom(
        routeAuthority: LeaderRouteAuthority,
        slot: LeaderSlot,
    ): LeaderRouteEvaluation = LeaderRouteEvaluation(
        decision = routeAuthority.evaluate(slot),
        leaderState = null,
        evaluatedAt = clock.instant(),
    )
}
