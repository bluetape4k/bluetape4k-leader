# 6계층 코드 검토 - Issue #345 Consul 런타임 일시 중지

범위: `leader-consul` 코루틴 단일 리더 런타임, 일시 중지 잠금 확장 위임, 취소 정리, 상태 매핑, 테스트, README 업데이트 및 이슈 #345 강의 캡처.

## 평결

- 지역 코덱스 6계층: P3 후속 메모로만 승인하세요.
- 클로드 6계층 고문: 승인합니다.
- 게이트: P0=0, P1=0.
- 클로드 유물:
  - `.omx/artifacts/claude-code-review-consul-suspend-final4-20260523000353.md`

## 계층 1 - 보안

- 통과: 소유자 페이로드는 내부 Consul 클라이언트 경계를 통해 봉인된 상태로 유지됩니다.
- 통과: 세션 범위 릴리스는 선택된 Consul 세션을 파괴하여 취소 또는 작업 실패 후 오래된 잠금 소유권을 방지합니다.
- 통과: ACL 토큰 또는 엔드포인트 자격 증명 자료가 suspend API 표면에 의해 기록되거나 방출되지 않습니다.

조사 결과: 없음 P0/P1.

## 계층 2 - 아키텍처/API

- 통과: `ConsulSuspendLeaderElector`는 핵심 인터페이스를 변경하지 않고 공유 `SuspendLeaderElector` 계약을 구현합니다.
- 통과: 일반 잠금 경합은 `null`를 반환하고, 선거 후 작업 실패는 `LeaderRunResult.ActionFailed`에 매핑됩니다.
- 통과: `state()`는 동기 상위 계약을 유지하고 차단 Consul 읽기 주의 사항을 문서화합니다.

조사 결과: 없음 P0/P1.

## 계층 3 - 동시성/취소

- 통과: 릴리스 정리는 `NonCancellable`에서 실행되므로 코루틴 취소는 `delayBeforeRelease`, 감시 닫기 또는 Consul 세션 삭제를 건너뛸 수 없습니다.
- PASS: 대기 중 후보 취소는 `CancellationException`를 다시 발생시키기 전에 후보 세션을 파괴합니다.
- 통과: 자동 확장 감시는 `minLeaseTime` 정리를 통해 활성 상태를 유지하고 최종 릴리스 전에 닫힙니다.
- PASS: `ConsulSuspendLockExtendDelegate`는 Consul 작업 일시 중지를 사용하고 `runBlocking`를 통해 브리지하지 않습니다.

조사 결과: 없음 P0/P1.

## 계층 4 - 정확성

- 통과: 누락되거나 유효하지 않은 소유자 페이로드는 이제 Consul 세션 ID를 감사 리더 ID로 조작하는 대신 `LeaderState.empty(...)`에 매핑됩니다.
- PASS: 대기 중인 후보자는 TTL이 만료되기 전에 자신의 세션을 갱신합니다.
- PASS: 슬롯 리더 ID가 기록되고 감사 ID로 다시 읽혀집니다.
- PASS: `state()` 동작은 블로킹 및 일시 중단 지원 Consul 리더 런타임 표면 간에 정렬됩니다.

조사 결과: 없음 P0/P1.

## 계층 5 - 테스트 / 유형 / 자동 실패

- 통과: 테스트에서는 경합 건너뛰기, 취소 정리, 작업 실패 정리, 대기 시간 초과 정리, 대기 후보 갱신 실패, 자동 확장 갱신, 일시 중지 잠금 어설션/확장 동작, 상태 매핑 및 확장 기능 동작을 다루고 있습니다.
- 통과: 통합 테스트는 실제 Consul에 대한 취소 및 작업 실패 후 다시 획득됨을 입증합니다.
- WATCH: 자동 확장 통합 테스트에는 벽시계 타이밍 민감도가 있습니다. CI 플레이크 증거가 나타나는 경우에만 P3로 추적합니다.

조사 결과: 없음 P0/P1.

## 계층 6 - 문서/운영

- 통과: 이제 모듈 및 루트 README 테이블에서 Consul를 차단/비동기/코루틴 단일 리더 런타임으로 설명합니다.
- 통과: 모듈 README에는 일시 중지 사용 예가 포함되어 있습니다.
- 통과: L9 및 L10 단원에서는 향후 Consul 슬라이스에 대한 NonCancellable 정리 및 `SuspendExtendDelegate` 요구 사항을 문서화합니다.

조사 결과: 없음 P0/P1.

## 검증

- `git diff --check`
- `./gradlew :bluetape4k-leader-consul:test --no-daemon --console=plain --rerun-tasks`
  - 통과: 42개 테스트.
- `./gradlew :bluetape4k-leader-consul:check --no-daemon --console=plain --rerun-tasks`
  - 통과: 42개 테스트 및 적용 범위 검증.
- Claude Code Advisor 아티팩트 `.omx/artifacts/claude-code-review-consul-suspend-final4-20260523000353.md`가 `Gate: PASS; P0=0; P1=0`를 보고했습니다.

## 후속 후보자

- 코어가 공개하는 경우 향후 일시 중단 관련 비차단 상태 쿼리를 고려하세요.
- Consul 그룹 선출, Spring 자동 구성 및 이벤트/메트릭 표면이 포함된 이슈 #345를 계속하세요.
