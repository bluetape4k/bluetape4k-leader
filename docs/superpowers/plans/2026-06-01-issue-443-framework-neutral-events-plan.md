# Issue 443 Framework-neutral Leader Events Plan

## 한국어 해설

이 문서는 `Issue 443 Framework-neutral Leader Events Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Implementation

1. Extend `LeaderElectionEventPublisher` with explicit-scope callback registration methods.
2. Add internal helper code that collects `events`, filters by event subtype, logs callback failures, and returns a cancellable `AutoCloseable`.
3. Make all `LeaderElectionEvent` payloads serializable with explicit serial version values.
4. Update `leader-core` README locale pair and top-level README locale pair with the event-publisher-first model.
5. Add a short lesson entry.

## Tests

1. Add unit tests for `onEvent` registration and close/unregister behavior.
2. Add unit tests for `onElected`, `onRevoked`, and `onSkipped` filtering.
3. Add a unit test proving a throwing callback does not stop the collector.
4. Add serialization tests for `Revoked` and `Skipped`.

## Verification

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderElectionEventTest' --tests 'io.bluetape4k.leader.LeaderElectionListenerTest' --no-daemon`
- `./gradlew :bluetape4k-leader-core:test --no-daemon`
- `git diff --check`
