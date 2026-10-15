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

/** history sink가 없을 때 Noop sink를 제공하고, sink와 MeterRegistry에 맞는 safe recorder를 구성합니다. */
@AutoConfiguration(before = [LeaderElectionAutoConfiguration::class])
@ConditionalOnProperty(prefix = "bluetape4k.leader.history", name = ["enabled"], matchIfMissing = true)
class LeaderHistoryAutoConfiguration {

    companion object: KLogging()

    // ----------------------------------------------------------------
    // Noop fallbacks — registered when no real sink bean is present
    // ----------------------------------------------------------------

    /** 사용자 sink가 없을 때 이벤트를 버리는 [NoopLeaderHistorySink]를 fallback으로 등록합니다. */
    @Bean
    @ConditionalOnMissingBean(LeaderHistorySink::class)
    fun leaderHistorySink(): LeaderHistorySink {
        log.info {
            "Leader history audit disabled (NoopLeaderHistorySink). " +
                    "Provide a LeaderHistorySink bean to enable."
        }
        return NoopLeaderHistorySink
    }

    /** 사용자 suspend sink가 없을 때 이벤트를 버리는 [NoopSuspendLeaderHistorySink]를 fallback으로 등록합니다. */
    @Bean
    @ConditionalOnMissingBean(SuspendLeaderHistorySink::class)
    fun suspendLeaderHistorySink(): SuspendLeaderHistorySink = NoopSuspendLeaderHistorySink

    // ----------------------------------------------------------------
    // Recorder beans — plain (no Micrometer)
    // ----------------------------------------------------------------

    /** 일반 sink를 감싸는 safe recorder를 등록하며 MeterRegistry가 있으면 micrometer 구현이 우선됩니다. */
    @Bean
    @ConditionalOnMissingBean(SafeLeaderHistoryRecorder::class)
    @ConditionalOnBean(LeaderHistorySink::class)
    fun safeLeaderHistoryRecorder(sink: LeaderHistorySink): SafeLeaderHistoryRecorder {
        logIfNoopSink(sink)
        return SafeLeaderHistoryRecorder(sink)
    }

    /** 단일 suspend sink가 있을 때 safe recorder를 등록하고, sink가 없거나 여러 개면 등록하지 않습니다. */
    @Bean
    @ConditionalOnMissingBean(SuspendSafeLeaderHistoryRecorder::class)
    fun suspendSafeLeaderHistoryRecorder(
        provider: ObjectProvider<SuspendLeaderHistorySink>,
    ): SuspendSafeLeaderHistoryRecorder? {
        val sinks = provider.toList()
        return when {
            sinks.isEmpty() -> null
            sinks.size > 1 -> {
                log.warn {
                    "Multiple SuspendLeaderHistorySink beans found; use @Primary to disambiguate. " +
                            "Suspend history recorder not created."
                }
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

    /** MeterRegistry가 있을 때 history recorder에 실패/누락 카운터를 추가하는 configuration입니다. */
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
                    log.warn {
                        "Multiple SuspendLeaderHistorySink beans found; use @Primary to disambiguate. " +
                                "Suspend history recorder not created."
                    }
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
