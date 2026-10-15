package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import kotlin.time.Duration

/**
 * Spring AOP가 전달하는 lock 시도·획득·건너뜀 및 사용자 작업 시작·종료 이벤트를 기록합니다.
 *
 * 기본 구현은 이벤트를 무시합니다. context overload는 기존 구현과 호환되도록 문맥을 경고하고 context 없는 overload로 위임합니다.
 */
interface LeaderAopMetricsRecorder {

    // =========================================================================
    // legacy overload: context를 받지 않는 6개 method입니다.
    // =========================================================================

    /**
     * lock 획득을 요청한 시점을 기록합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param options 해당 요청에 적용한 대기 시간, lease TTL 및 연장 정책입니다.
     */
    fun onLockAttempt(name: String, options: LeaderElectionOptions) {}

    /**
     * lock 획득에 성공한 시점과 대기 시간을 기록합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param options 해당 요청에 적용한 대기 시간, lease TTL 및 연장 정책입니다.
     * @param acquireElapsed lock 획득 시도부터 성공 응답까지의 경과 시간입니다.
     */
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {}

    /**
     * lock을 얻지 못한 시점과 건너뛴 원인을 기록합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param options 해당 요청에 적용한 대기 시간, lease TTL 및 연장 정책입니다.
     * @param reason lock 획득이 완료되지 않은 원인 분류입니다.
     */
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {}

    /**
     * leadership을 얻은 뒤 사용자 작업이 시작된 시점을 기록합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     */
    fun onTaskStarted(name: String) {}

    /**
     * 사용자 작업이 정상 종료된 시점과 실행 시간을 기록합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param executionTime 사용자 작업 실행 시간입니다.
     */
    fun onTaskFinished(name: String, executionTime: Duration) {}

    /**
     * 사용자 작업이 예외로 끝난 시점과 실행 시간을 기록합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param executionTime 사용자 작업 실행 시간입니다.
     * @param throwable 사용자 작업에서 발생한 예외입니다.
     */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {}

    // =========================================================================
    // context-bearing overload: context를 받는 6개 method입니다.
    // 기본 구현은 LeaderRecorderContextDropLog를 통해 context를 버리고 legacy overload로 위임합니다.
    // leader ID 정보를 수집하려면 이 overload를 override합니다.
    // =========================================================================

    /**
     * lock 획득을 요청한 시점을 기록합니다.
     * 이 overload는 leader ID 문맥을 포함합니다. 기본 구현은 문맥을 경고 로그에 알리고 문맥 없는 overload로 위임합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param options 해당 요청에 적용한 대기 시간, lease TTL 및 연장 정책입니다.
     * @param context 현재 AOP 실행에 연결된 leader ID 문맥입니다. 식별할 수 없으면 `Unknown`입니다.
     */
    fun onLockAttempt(name: String, options: LeaderElectionOptions, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockAttempt(name, options)
    }

    /**
     * lock 획득에 성공한 시점과 대기 시간을 기록합니다.
     * 이 overload는 leader ID 문맥을 포함합니다. 기본 구현은 문맥을 경고 로그에 알리고 문맥 없는 overload로 위임합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param options 해당 요청에 적용한 대기 시간, lease TTL 및 연장 정책입니다.
     * @param acquireElapsed lock 획득 시도부터 성공 응답까지의 경과 시간입니다.
     * @param context 현재 AOP 실행에 연결된 leader ID 문맥입니다. 식별할 수 없으면 `Unknown`입니다.
     */
    fun onLockAcquired(
        name: String,
        options: LeaderElectionOptions,
        acquireElapsed: Duration,
        context: LeaderAopMetricsContext,
    ) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockAcquired(name, options, acquireElapsed)
    }

    /**
     * lock을 얻지 못한 시점과 건너뛴 원인을 기록합니다.
     * 이 overload는 leader ID 문맥을 포함합니다. 기본 구현은 문맥을 경고 로그에 알리고 문맥 없는 overload로 위임합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param options 해당 요청에 적용한 대기 시간, lease TTL 및 연장 정책입니다.
     * @param reason lock 획득이 완료되지 않은 원인 분류입니다.
     * @param context 현재 AOP 실행에 연결된 leader ID 문맥입니다. 식별할 수 없으면 `Unknown`입니다.
     */
    fun onLockNotAcquired(
        name: String,
        options: LeaderElectionOptions,
        reason: SkipReason,
        context: LeaderAopMetricsContext,
    ) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockNotAcquired(name, options, reason)
    }

    /**
     * leadership을 얻은 뒤 사용자 작업이 시작된 시점을 기록합니다.
     * 이 overload는 leader ID 문맥을 포함합니다. 기본 구현은 문맥을 경고 로그에 알리고 문맥 없는 overload로 위임합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param context 현재 AOP 실행에 연결된 leader ID 문맥입니다. 식별할 수 없으면 `Unknown`입니다.
     */
    fun onTaskStarted(name: String, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskStarted(name)
    }

    /**
     * 사용자 작업이 정상 종료된 시점과 실행 시간을 기록합니다.
     * 이 overload는 leader ID 문맥을 포함합니다. 기본 구현은 문맥을 경고 로그에 알리고 문맥 없는 overload로 위임합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param executionTime 사용자 작업 실행 시간입니다.
     * @param context 현재 AOP 실행에 연결된 leader ID 문맥입니다. 식별할 수 없으면 `Unknown`입니다.
     */
    fun onTaskFinished(name: String, executionTime: Duration, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskFinished(name, executionTime)
    }

    /**
     * 사용자 작업이 예외로 끝난 시점과 실행 시간을 기록합니다.
     * 이 overload는 leader ID 문맥을 포함합니다. 기본 구현은 문맥을 경고 로그에 알리고 문맥 없는 overload로 위임합니다.
     * @param name AOP가 해석한 lock 이름입니다.
     * @param executionTime 사용자 작업 실행 시간입니다.
     * @param throwable 사용자 작업에서 발생한 예외입니다.
     * @param context 현재 AOP 실행에 연결된 leader ID 문맥입니다. 식별할 수 없으면 `Unknown`입니다.
     */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskFailed(name, executionTime, throwable)
    }

    /** 모든 이벤트를 무시하는 recorder입니다. */
    object NoOp: LeaderAopMetricsRecorder
}
