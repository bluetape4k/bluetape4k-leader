package io.bluetape4k.leader.spring.history

import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `LeaderHistoryAutoConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@AutoConfiguration(before = [LeaderElectionAutoConfiguration::class])
@ConditionalOnProperty(prefix = "bluetape4k.leader.history", name = ["enabled"], matchIfMissing = true)
class LeaderHistoryAutoConfiguration {

    companion object : KLogging()

    // ----------------------------------------------------------------
    // Noop fallbacks — registered when no real sink bean is present
    // ----------------------------------------------------------------

    /**
     * `leaderHistorySink` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderHistorySink::class)
    fun leaderHistorySink(): LeaderHistorySink {
        log.info { "Leader history audit disabled (NoopLeaderHistorySink). Provide a LeaderHistorySink bean to enable." }
        return NoopLeaderHistorySink
    }

    /**
     * `suspendLeaderHistorySink` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Bean
    @ConditionalOnMissingBean(SuspendLeaderHistorySink::class)
    fun suspendLeaderHistorySink(): SuspendLeaderHistorySink = NoopSuspendLeaderHistorySink

    // ----------------------------------------------------------------
    // Recorder beans — plain (no Micrometer)
    // ----------------------------------------------------------------

    /**
     * `safeLeaderHistoryRecorder` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Bean
    @ConditionalOnMissingBean(SafeLeaderHistoryRecorder::class)
    @ConditionalOnBean(LeaderHistorySink::class)
    fun safeLeaderHistoryRecorder(sink: LeaderHistorySink): SafeLeaderHistoryRecorder {
        logIfNoopSink(sink)
        return SafeLeaderHistoryRecorder(sink)
    }

    /**
     * `suspendSafeLeaderHistoryRecorder` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Bean
    @ConditionalOnMissingBean(SuspendSafeLeaderHistoryRecorder::class)
    fun suspendSafeLeaderHistoryRecorder(
        provider: ObjectProvider<SuspendLeaderHistorySink>,
    ): SuspendSafeLeaderHistoryRecorder? {
        val sinks = provider.toList()
        return when {
            sinks.isEmpty() -> null
            sinks.size > 1 -> {
                log.warn { "Multiple SuspendLeaderHistorySink beans found; use @Primary to disambiguate. Suspend history recorder not created." }
                null
            }
            else -> SuspendSafeLeaderHistoryRecorder(sinks.single())
        }
    }

    private fun logIfNoopSink(resolved: LeaderHistorySink) {
        if (resolved === NoopLeaderHistorySink) {
            log.info { "LeaderHistorySink is NoopLeaderHistorySink — history events will be discarded." }
        }
    }

    // ----------------------------------------------------------------
    // Micrometer-instrumented recorders (conditional on MeterRegistry)
    // ----------------------------------------------------------------

    /**
     * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
        name = [
            "io.micrometer.core.instrument.MeterRegistry",
            "io.bluetape4k.leader.micrometer.history.MicrometerSafeLeaderHistoryRecorder",
        ]
    )
    class MicrometerHistoryConfig {

        @Bean
        @ConditionalOnMissingBean(SafeLeaderHistoryRecorder::class)
        @ConditionalOnBean(LeaderHistorySink::class)
        fun micrometerSafeLeaderHistoryRecorder(
            sink: LeaderHistorySink,
            registryProvider: ObjectProvider<io.micrometer.core.instrument.MeterRegistry>,
        ): SafeLeaderHistoryRecorder {
            val registry = registryProvider.ifAvailable
            return if (registry != null) {
                io.bluetape4k.leader.micrometer.history.MicrometerSafeLeaderHistoryRecorder(sink, registry)
            } else {
                SafeLeaderHistoryRecorder(sink)
            }
        }

        @Bean
        @ConditionalOnMissingBean(SuspendSafeLeaderHistoryRecorder::class)
        fun micrometerSuspendSafeLeaderHistoryRecorder(
            provider: ObjectProvider<SuspendLeaderHistorySink>,
            registryProvider: ObjectProvider<io.micrometer.core.instrument.MeterRegistry>,
        ): SuspendSafeLeaderHistoryRecorder? {
            val sinks = provider.toList()
            return when {
                sinks.isEmpty() -> null
                sinks.size > 1 -> {
                    log.warn { "Multiple SuspendLeaderHistorySink beans found; use @Primary to disambiguate. Suspend history recorder not created." }
                    null
                }
                else -> {
                    val sink = sinks.single()
                    val registry = registryProvider.ifAvailable
                    if (registry != null) {
                        io.bluetape4k.leader.micrometer.history.MicrometerSuspendSafeLeaderHistoryRecorder(
                            sink,
                            registry,
                        )
                    } else {
                        SuspendSafeLeaderHistoryRecorder(sink)
                    }
                }
            }
        }
    }
}
