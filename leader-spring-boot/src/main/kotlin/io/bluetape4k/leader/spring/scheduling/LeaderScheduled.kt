package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import org.springframework.core.annotation.AliasFor
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

/**
 * `LeaderScheduled`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property name Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property cron Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property zone Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property fixedRate Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property fixedRateString Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property fixedDelay Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property fixedDelayString Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property initialDelay Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property initialDelayString Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property timeUnit Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property scheduler Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property waitTime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property leaseTime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property minLeaseTime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property autoExtend Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property streamBounded Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property bean Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property failureMode Spring Boot integration 계약에서 사용하는 속성입니다.
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
