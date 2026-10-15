package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import org.springframework.core.annotation.AliasFor
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit

/**
 * Spring `@Scheduled`와 leader election을 하나의 annotation으로 결합합니다.
 *
 * `cron`, fixed rate/delay, initial delay, time unit, scheduler는 Spring `@Scheduled`로 alias되고,
 * wait/lease와 failure 정책은 `@LeaderElection`으로 alias됩니다.
 *
 * @property name leader lock 이름이며 비워 둘 수 없습니다.
 * @property cron Spring cron 표현식이며 기본값은 빈 문자열입니다.
 * @property zone cron이 해석될 시간대이며 기본값은 JVM default입니다.
 * @property fixedRate 이전 invocation 시작부터의 고정 주기이며 기본값 `-1`은 미설정 sentinel입니다.
 * @property fixedRateString property 또는 duration 문자열로 지정하는 fixed rate이며 기본값은 빈 문자열입니다.
 * @property fixedDelay 이전 invocation 완료부터의 고정 지연이며 기본값 `-1`은 미설정 sentinel입니다.
 * @property fixedDelayString property 또는 duration 문자열로 지정하는 fixed delay이며 기본값은 빈 문자열입니다.
 * @property initialDelay 첫 invocation 전 지연이며 기본값 `-1`은 미설정 sentinel입니다.
 * @property initialDelayString property 또는 duration 문자열로 지정하는 initial delay이며 기본값은 빈 문자열입니다.
 * @property timeUnit 숫자 schedule 값의 단위이며 기본값은 milliseconds입니다.
 * @property scheduler schedule 실행에 사용할 Spring `TaskScheduler` bean 이름이며 기본값은 빈 문자열입니다.
 * @property waitTime lock 획득 대기 기간이며 빈 문자열이면 공통 AOP 기본값을 사용합니다.
 * @property leaseTime lease 기간이며 빈 문자열이면 공통 AOP 기본값을 사용합니다.
 * @property minLeaseTime 허용할 최소 lease 기간이며 기본값은 `PT0S`입니다.
 * @property autoExtend 실행 중 lease 자동 연장 여부이며 기본값은 `false`입니다.
 * @property streamBounded stream 결과가 lease 안에 종료된다고 선언할지 여부이며 기본값은 `false`입니다.
 * @property bean 사용할 elector factory bean 이름이며 빈 문자열이면 selector 자동 선택을 사용합니다.
 * @property failureMode backend 오류 또는 lock 미획득 처리 정책이며 기본값 `INHERIT`는 AOP 공통 설정을 따릅니다.
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
