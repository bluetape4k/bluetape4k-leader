package io.bluetape4k.leader.spring.aop.internal

/**
 * AOP aspect 가 elector → aspect 간 handle capture 불변식 위반을 감지할 때 throw.
 *
 * ## 불변식
 * group elector 의 `CaptureScope.runWithCapture` / `runWithCaptureSuspend` 는 action 호출 직전
 * `LeaderLockHandleCapture.set(handle)` 을 호출합니다. aspect 는 action lambda 의 첫 statement 에서
 * `AopScopeAccess.pollCapture()` 로 handle 을 수신합니다.
 *
 * **single elector 는 CaptureScope 를 사용하지 않으므로 pollCapture() == null 은 정상** — 이 예외는
 * group elector 전용 capture 실패 시만 throw 합니다.
 *
 * silent fail-open 변환은 절대 금지 (Codex F10 / SF3).
 */
internal class CaptureInvariantException(message: String) : IllegalStateException(message)
