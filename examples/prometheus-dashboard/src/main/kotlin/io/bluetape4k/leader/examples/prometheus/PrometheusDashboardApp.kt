package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
@EnableAspectJAutoProxy(proxyTargetClass = true)
class PrometheusDashboardApp {

    @Bean(destroyMethod = "shutdown")
    fun redisClient(
        @Value("\${demo.redis.url:}") configuredRedisUrl: String,
    ): RedisClient {
        val redisUrl = configuredRedisUrl
            .ifBlank { RedisServer.Launcher.redis.url }
        return RedisClient.create(redisUrl)
    }

    @Bean(destroyMethod = "close")
    fun redisConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8)

    // Keep the recorder explicit so pre-registration works in normal and AOT test contexts.
    @Bean
    @ConditionalOnMissingBean
    fun micrometerLeaderAopMetricsRecorder(registry: MeterRegistry): MicrometerLeaderAopMetricsRecorder =
        MicrometerLeaderAopMetricsRecorder(registry)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(PrometheusDashboardApp::class.java, *args)
        }
    }
}

@Component
class LeaderMetricsPreRegistrar(
    private val recorder: MicrometerLeaderAopMetricsRecorder,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun registerStaticLocks() {
        recorder.registerMetricsFor(LeaderScheduledJob.LOCK_NAME)
    }
}

@Component
class LeaderScheduledTrigger(
    private val job: LeaderScheduledJob,
) {

    @Scheduled(
        fixedDelayString = "\${demo.job.fixed-delay-ms:5000}",
        initialDelayString = "\${demo.job.initial-delay-ms:1000}",
    )
    fun tick() {
        job.dispatchBatch()
    }
}

@Component
class LeaderScheduledJob {

    private val executions = AtomicLong()

    @LeaderElection(
        name = LOCK_NAME,
        bean = "lettuceLeaderElectionFactory",
        waitTime = "PT1S",
        leaseTime = "PT30S",
    )
    fun dispatchBatch() {
        val count = executions.incrementAndGet()
        log.info { "leader dashboard job executed count=$count" }
        // Intentional demo latency so execution duration is visible in Prometheus.
        Thread.sleep(100)
    }

    fun executionCount(): Long = executions.get()

    companion object: KLogging() {
        const val LOCK_NAME: String = "dashboard-job"
    }
}
