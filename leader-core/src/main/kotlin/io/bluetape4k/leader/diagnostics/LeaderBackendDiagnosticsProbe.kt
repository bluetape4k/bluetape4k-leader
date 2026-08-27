package io.bluetape4k.leader.diagnostics

import java.time.Clock
import java.util.concurrent.CancellationException
import kotlin.time.Duration

/**
 * Framework에 종속되지 않은 동기 diagnostics probe 경계입니다.
 *
 * 이 helper는 Kotlin/JVM 호출자를 위한 stateless 함수형 facade입니다. 기존 provider가
 * 전달한 timeout을 provider-native bounded budget으로만 사용하며, wall-clock deadline,
 * I/O, lock, lease, client, retry, thread, executor를 생성하거나 관리하지 않습니다.
 * callback의 일반적인 [Exception]은 `UNKNOWN`으로 정규화하지만,
 * [CancellationException]과 [InterruptedException]은 동일 인스턴스로 재전파하고
 * interrupt flag를 복원합니다. 치명적인 [Error]도 숨기지 않고 재전파합니다.
 */
public object LeaderBackendDiagnosticsProbe {

    /**
     * 기존 client 상태를 한 번 확인하고 framework-neutral connectivity 결과로 매핑합니다.
     *
     * [timeout]은 양수 유한 provider-native budget이어야 하며, callback은 호출 thread에서
     * 한 번만 실행됩니다. [clock] 읽기는 callback보다 먼저 한 번 수행합니다.
     */
    public fun check(
        timeout: Duration,
        clock: Clock = Clock.systemUTC(),
        probe: (Duration) -> LeaderBackendConnectivityStatus,
    ): LeaderBackendConnectivity =
        check(
            timeout = timeout,
            clock = clock,
            unknownReason = LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED,
            probe = probe,
        )

    /**
     * 기존 client 상태를 한 번 확인하고 caller가 지정한 UNKNOWN 원인으로 매핑합니다.
     *
     * 기존 3-argument overload를 보존해 source와 JVM 호출자의 호출 순서를 유지합니다.
     */
    public fun check(
        timeout: Duration,
        clock: Clock = Clock.systemUTC(),
        unknownReason: LeaderBackendConnectivityReason =
            LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED,
        probe: (Duration) -> LeaderBackendConnectivityStatus,
    ): LeaderBackendConnectivity {
        val validTimeout = timeout.requirePositiveFiniteProbeTimeout()
        require(
            unknownReason == LeaderBackendConnectivityReason.PROVIDER_UNSUPPORTED ||
                unknownReason == LeaderBackendConnectivityReason.PROVIDER_EXCEPTION ||
                unknownReason == LeaderBackendConnectivityReason.CLIENT_STATE_UNCONFIRMED,
        ) {
            "unknownReason must describe an UNKNOWN connectivity result: $unknownReason"
        }
        val checkedAt = clock.instant()
        val status = try {
            probe(validTimeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            return LeaderBackendConnectivity.unknown(
                checkedAt,
                reason = LeaderBackendConnectivityReason.PROVIDER_EXCEPTION,
            )
        }

        return when (status) {
            LeaderBackendConnectivityStatus.UP -> LeaderBackendConnectivity.up(checkedAt)
            LeaderBackendConnectivityStatus.DOWN -> LeaderBackendConnectivity.down(checkedAt)
            LeaderBackendConnectivityStatus.UNKNOWN ->
                LeaderBackendConnectivity.unknown(checkedAt, reason = unknownReason)
            LeaderBackendConnectivityStatus.NOT_CHECKED -> invalidProbeStatus()
        }
    }

    private fun invalidProbeStatus(): Nothing =
        throw IllegalArgumentException("probe callback must return a checked connectivity status")
}
