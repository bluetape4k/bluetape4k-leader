# 문제 443 프레임워크 중립 이벤트

## 맥락

Milestone 0.3.0 Issue #443에서는 Spring Boot, Ktor, Micrometer 또는 추적 관련 계약을 강제하지 않는 핵심 관찰성 API를 요청했습니다.

## 결정

`LeaderElectionEventPublisher.events`를 코루틴 기본 소스로 유지하고 동일한 게시자에 명시적 범위 콜백 등록 방법을 추가합니다. 콜백 사용자는 라이프사이클 소유 `CoroutineScope`를 제공해야 합니다. core는 숨겨진 전역 범위를 생성하지 않습니다.

## 결과

Spring/Ktor/Micrometer/로깅/추적 어댑터는 이제 `onEvent`, `onElected`, `onRevoked` 및 `onSkipped` 핸들을 통해 동일한 핵심 이벤트 스트림을 사용할 수 있습니다.

## 검증

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderElectionEventTest' --tests 'io.bluetape4k.leader.LeaderElectionListenerTest' --no-daemon`는 38개의 이벤트 중심 테스트를 통과했습니다.
- `./gradlew :bluetape4k-leader-core:test --no-daemon`는 703개의 테스트를 통과했습니다.
- `git diff --check`가 통과되었습니다.
