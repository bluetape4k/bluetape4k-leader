package io.bluetape4k.leader.spring.history

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.local.LocalLeaderElectorFactory
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LeaderHistoryRetentionAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaderHistoryRetentionAutoConfiguration::class.java))

    private val aopRunner = ApplicationContextRunner()
        .withBean(LeaderElectorFactory::class.java, { LocalLeaderElectorFactory() })
        .withBean(SuspendLeaderElectorFactory::class.java, { LocalSuspendLeaderElectorFactory() })
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopAutoConfiguration::class.java,
                LeaderHistoryRetentionAutoConfiguration::class.java,
            )
        )

    @Test
    fun `suspend retention job is created without blocking LeaderElector`() {
        aopRunner
            .withBean(SuspendLeaderHistorySink::class.java, { NoopSuspendLeaderHistorySink })
            .run { ctx ->
                ctx.getBeanNamesForType(SuspendLeaderHistoryRetentionJob::class.java).size shouldBeEqualTo 1
                ctx.getBeanNamesForType(LeaderHistoryRetentionJob::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun `retention jobs are not created without matching elector beans`() {
        runner
            .withBean(SuspendLeaderHistorySink::class.java, { NoopSuspendLeaderHistorySink })
            .withBean(LeaderHistorySink::class.java, { NoopLeaderHistorySink })
            .run { ctx ->
                ctx.getBeanNamesForType(SuspendLeaderHistoryRetentionJob::class.java).size shouldBeEqualTo 0
                ctx.getBeanNamesForType(LeaderHistoryRetentionJob::class.java).size shouldBeEqualTo 0
            }
    }
}
