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
 * Auto-configuration for the leader-history audit contract (Issue #50).
 *
 * ## Load order
 * This configuration must be loaded **before** [LeaderElectionAutoConfiguration] so that
 * `SafeLeaderHistoryRecorder` / `SuspendSafeLeaderHistoryRecorder` beans are available when
 * elector beans are created.  The ordering is declared via `before` and is also reflected
 * in the `AutoConfiguration.imports` file where this class appears first.
 *
 * ## Behaviour
 * - When no [LeaderHistorySink] bean is present, a [NoopLeaderHistorySink] fallback is
 *   registered and an INFO log warns that history audit is disabled.
 * - When exactly one [LeaderHistorySink] bean is present, a [SafeLeaderHistoryRecorder]
 *   wrapping it is registered.  If Micrometer is on the classpath, a
 *   `MicrometerSafeLeaderHistoryRecorder` is preferred over the plain variant.
 * - The [SuspendLeaderHistorySink] path mirrors the blocking path using `ObjectProvider.ifUnique`
 *   to avoid `NoUniqueBeanDefinitionException` when multiple suspend-sink beans are present.
 * - When multiple sinks of the same type are found, a WARN is logged asking the user to add
 *   `@Primary` to disambiguate.
 *
 * ## Configuration property
 * `bluetape4k.leader.history.enabled` (default `true`) — set to `false` to disable entirely.
 */
@AutoConfiguration(before = [LeaderElectionAutoConfiguration::class])
@ConditionalOnProperty(prefix = "bluetape4k.leader.history", name = ["enabled"], matchIfMissing = true)
class LeaderHistoryAutoConfiguration {

    companion object : KLogging()

    // ----------------------------------------------------------------
    // Noop fallbacks — registered when no real sink bean is present
    // ----------------------------------------------------------------

    /**
     * Fallback [LeaderHistorySink] that discards all events.
     * Logs an INFO message so operators know history audit is inactive.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderHistorySink::class)
    fun leaderHistorySink(): LeaderHistorySink {
        log.info { "Leader history audit disabled (NoopLeaderHistorySink). Provide a LeaderHistorySink bean to enable." }
        return NoopLeaderHistorySink
    }

    /**
     * Fallback [SuspendLeaderHistorySink] that discards all events.
     */
    @Bean
    @ConditionalOnMissingBean(SuspendLeaderHistorySink::class)
    fun suspendLeaderHistorySink(): SuspendLeaderHistorySink = NoopSuspendLeaderHistorySink

    // ----------------------------------------------------------------
    // Recorder beans — plain (no Micrometer)
    // ----------------------------------------------------------------

    /**
     * Plain [SafeLeaderHistoryRecorder] registered when Micrometer is **not** on the classpath.
     * When Micrometer is present, [MicrometerHistoryConfig] provides the instrumented variant.
     */
    @Bean
    @ConditionalOnMissingBean(SafeLeaderHistoryRecorder::class)
    @ConditionalOnBean(LeaderHistorySink::class)
    fun safeLeaderHistoryRecorder(sink: LeaderHistorySink): SafeLeaderHistoryRecorder {
        warnIfMultipleSinks(sink)
        return SafeLeaderHistoryRecorder(sink)
    }

    /**
     * Plain [SuspendSafeLeaderHistoryRecorder].  Uses `ObjectProvider.ifUnique` to avoid
     * `NoUniqueBeanDefinitionException` when multiple suspend-sink beans are present.
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

    private fun warnIfMultipleSinks(resolved: LeaderHistorySink) {
        if (resolved === NoopLeaderHistorySink) {
            log.info { "LeaderHistorySink is NoopLeaderHistorySink — history events will be discarded." }
        }
    }

    // ----------------------------------------------------------------
    // Micrometer-instrumented recorders (conditional on MeterRegistry)
    // ----------------------------------------------------------------

    /**
     * Inner configuration activated when `io.micrometer.core.instrument.MeterRegistry` is present.
     * Provides instrumented recorder beans that take precedence over the plain variants.
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
