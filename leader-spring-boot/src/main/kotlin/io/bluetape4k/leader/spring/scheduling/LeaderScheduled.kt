package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import org.springframework.core.annotation.AliasFor
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

/**
 * Runs a Spring scheduled method only when this node acquires the named leader lease.
 *
 * This is a composed convenience annotation. Spring still owns trigger registration and scheduled
 * task observation, while the existing leader-election aspect owns acquisition, contention skips,
 * lease handling, and leader metrics.
 *
 * Exactly one of [cron], [fixedRate], [fixedRateString], [fixedDelay], or [fixedDelayString] must
 * define the schedule, following Spring's [Scheduled] contract.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Scheduled
@LeaderElection(name = "")
annotation class LeaderScheduled(
    @get:AliasFor(annotation = LeaderElection::class, attribute = "name")
    val name: String,

    @get:AliasFor(annotation = Scheduled::class, attribute = "cron")
    val cron: String = "",

    @get:AliasFor(annotation = Scheduled::class, attribute = "zone")
    val zone: String = "",

    @get:AliasFor(annotation = Scheduled::class, attribute = "fixedRate")
    val fixedRate: Long = -1,

    @get:AliasFor(annotation = Scheduled::class, attribute = "fixedRateString")
    val fixedRateString: String = "",

    @get:AliasFor(annotation = Scheduled::class, attribute = "fixedDelay")
    val fixedDelay: Long = -1,

    @get:AliasFor(annotation = Scheduled::class, attribute = "fixedDelayString")
    val fixedDelayString: String = "",

    @get:AliasFor(annotation = Scheduled::class, attribute = "initialDelay")
    val initialDelay: Long = -1,

    @get:AliasFor(annotation = Scheduled::class, attribute = "initialDelayString")
    val initialDelayString: String = "",

    @get:AliasFor(annotation = Scheduled::class, attribute = "timeUnit")
    val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,

    @get:AliasFor(annotation = Scheduled::class, attribute = "scheduler")
    val scheduler: String = "",

    @get:AliasFor(annotation = LeaderElection::class, attribute = "waitTime")
    val waitTime: String = "",

    @get:AliasFor(annotation = LeaderElection::class, attribute = "leaseTime")
    val leaseTime: String = "",

    @get:AliasFor(annotation = LeaderElection::class, attribute = "minLeaseTime")
    val minLeaseTime: String = "PT0S",

    @get:AliasFor(annotation = LeaderElection::class, attribute = "autoExtend")
    val autoExtend: Boolean = false,

    @get:AliasFor(annotation = LeaderElection::class, attribute = "streamBounded")
    val streamBounded: Boolean = false,

    @get:AliasFor(annotation = LeaderElection::class, attribute = "bean")
    val bean: String = "",

    @get:AliasFor(annotation = LeaderElection::class, attribute = "failureMode")
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
)
