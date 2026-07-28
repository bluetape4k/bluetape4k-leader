package io.bluetape4k.leader.metrics

import io.bluetape4k.leader.LeaderElectionOptions
import kotlin.time.Duration

/**
 * `LeaderAopMetricsRecorder`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderAopMetricsRecorder {

    // =========================================================================
    // legacy overload: context를 받지 않는 6개 method입니다.
    // =========================================================================

    /**
     * `onLockAttempt` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onLockAttempt(name: String, options: LeaderElectionOptions) {}

    /**
     * `onLockAcquired` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @param acquireElapsed `acquireElapsed` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {}

    /**
     * `onLockNotAcquired` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @param reason `reason` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {}

    /**
     * `onTaskStarted` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onTaskStarted(name: String) {}

    /**
     * `onTaskFinished` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param executionTime `executionTime` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onTaskFinished(name: String, executionTime: Duration) {}

    /**
     * `onTaskFailed` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param executionTime `executionTime` 호출 또는 상태 계산에 필요한 값입니다.
     * @param throwable `throwable` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {}

    // =========================================================================
    // context-bearing overload: context를 받는 6개 method입니다.
    // 기본 구현은 LeaderRecorderContextDropLog를 통해 context를 버리고 legacy overload로 위임합니다.
    // leader ID 정보를 수집하려면 이 overload를 override합니다.
    // =========================================================================

    /**
     * `onLockAttempt` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onLockAttempt(name: String, options: LeaderElectionOptions, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockAttempt(name, options)
    }

    /**
     * `onLockAcquired` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @param acquireElapsed `acquireElapsed` 호출 또는 상태 계산에 필요한 값입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockAcquired(name, options, acquireElapsed)
    }

    /**
     * `onLockNotAcquired` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param options `options` 호출 또는 상태 계산에 필요한 값입니다.
     * @param reason `reason` 호출 또는 상태 계산에 필요한 값입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onLockNotAcquired(name, options, reason)
    }

    /**
     * `onTaskStarted` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onTaskStarted(name: String, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskStarted(name)
    }

    /**
     * `onTaskFinished` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param executionTime `executionTime` 호출 또는 상태 계산에 필요한 값입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onTaskFinished(name: String, executionTime: Duration, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskFinished(name, executionTime)
    }

    /**
     * `onTaskFailed` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param name 호출자가 전달하는 이름 또는 key입니다.
     * @param executionTime `executionTime` 호출 또는 상태 계산에 필요한 값입니다.
     * @param throwable `throwable` 호출 또는 상태 계산에 필요한 값입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable, context: LeaderAopMetricsContext) {
        LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)
        onTaskFailed(name, executionTime, throwable)
    }

    /**
     * `NoOp` 선언은 leader election 계약에서 사용되는 object입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     */
    object NoOp : LeaderAopMetricsRecorder
}
