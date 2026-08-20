# Issue #733 — terminal delegate 실패·취소 뒤 audit pending context 정리

## 맥락

OBS-03의 `ExportingLeaderHistorySink`와 `ExportingSuspendLeaderHistorySink`는
`recordAcquired`에서 저장한 bounded pending context를 terminal history event에
재사용합니다. 기존 구현은 `recordCompleted` 또는 `recordFailed` delegate가 정상
반환한 뒤에만 context를 제거했습니다. 따라서 delegate 예외나 suspend cancellation이
발생하면 context가 TTL까지 남고, 실패가 집중될 때 bounded store capacity를 stale
entry가 점유할 수 있었습니다.

## 원인

terminal lifecycle의 context ownership이 delegate 성공 경로에만 묶여 있었습니다.
blocking sink는 delegate 호출이 예외를 던지면 `contexts.remove(key)`에 도달하지
않았고, suspend sink는 delegate 뒤의 `ensureActive()`가 cancellation을 재전파하면
동일하게 제거를 건너뛰었습니다.

## 결정

- blocking과 suspend sink의 `recordCompleted`/`recordFailed`를 `try/finally`로
  감싸 terminal delegate 예외·cancellation에서도 `contexts.remove(key)`를
  실행합니다.
- 정상 경로에서는 기존 순서대로 context를 먼저 제거한 뒤 audit export를
  submission하고, `finally`의 두 번째 제거는 no-op으로 둡니다. 따라서 exporter
  submission 실패와 delegate 예외의 전파 순서는 바뀌지 않습니다.
- suspend sink의 호출 전·호출 후 `ensureActive()`와 `CancellationException`
  재전파 계약은 유지합니다. context 제거는 non-suspending store 연산이므로
  cancellation을 차단하는 별도 `NonCancellable` 범위를 추가하지 않습니다.

## 결과

delegate가 `recordCompleted`/`recordFailed`에서 실패하거나 suspend terminal
호출 뒤 cancellation이 발생해도 pending context가 즉시 제거됩니다. 정상 history
event의 metadata와 redaction, exporter의 best-effort 격리, delegate 예외 및
cancellation 재전파는 기존 계약을 유지합니다. 동일 key의 후속 terminal event는
남아 있는 context 대신 `audit_context=missing` fallback을 사용합니다.

## 검증

- RED: 새 blocking exception 2개와 suspend terminal cancellation 회귀가
  `6 passing, 3 failing`으로 기존 구현의 잔류를 재현했습니다.
- GREEN: 최종 `ExportingLeaderHistorySinkTest`가 `9 passing`으로 통과했습니다.
- `./gradlew :bluetape4k-leader-core:test --no-daemon --rerun-tasks`가
  `960 passing`으로 통과했습니다.
- `./gradlew :bluetape4k-leader-core:detekt --no-daemon --rerun-tasks`와
  `git diff --check`가 통과했습니다.
- Korean terminology audit가 `findings=0`으로 통과했습니다.

## 놀라움과 복구

첫 회귀 테스트는 기본 `LeaderAuditValueSanitizer`를 사용해
`audit_context` 키 자체가 `redacted`로 바뀌면서 context 유무를 판별하지 못했습니다.
기존 missing-context 테스트와 동일하게 명시적 `Truncate` sanitizer를 사용해
`audit_context=missing` fallback을 검증하도록 수정했습니다.

## 향후 지침

pending context처럼 terminal callback이 소유한 bounded state는 정상 반환만으로
수명을 닫지 말고 delegate 호출, post-call cancellation, export submission을
포함하는 전체 terminal `finally` 경로에서 정리합니다. 회귀 테스트는 예외 전파나
cancellation 전파만 확인하지 말고 동일 key의 후속 terminal event가 missing
fallback으로 전환되는지 함께 확인합니다.
