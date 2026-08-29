package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

/**
 * Prometheus alert rule이 읽는 backend connectivity counter를 주기적으로 생성합니다.
 *
 * 기존 elector가 보유한 diagnostics provider를 재사용하고, probe timeout은 설정 가능한
 * 양수 millisecond 값으로 제한해 scheduler 호출이 무기한 대기하지 않도록 합니다.
 */
@Component
class PrometheusBackendConnectivityProbe(
    @Qualifier("prometheusBackendDiagnosticsProvider")
    private val provider: LeaderBackendDiagnosticsProvider,
    @Value("\${demo.backend-probe.timeout-ms:500}") timeoutMillis: Long,
) {

    private val timeout = timeoutMillis
        .requirePositiveNumber("demo.backend-probe.timeout-ms")
        .milliseconds

    @Scheduled(
        fixedDelayString = "\${demo.backend-probe.fixed-delay-ms:5000}",
        initialDelayString = "\${demo.backend-probe.initial-delay-ms:1000}",
    )
    fun probe() {
        provider.checkConnectivity(timeout)
    }
}
