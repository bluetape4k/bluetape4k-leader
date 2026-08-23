# 2026-08-23 Issue #744 executor rejection 전체 batch 계약

## 맥락

`BoundedLeaderAuditExporter`의 executor rejection 회귀 테스트는 기존에
`queueCapacity = 2`에서 rejection 대상 한 건과 recovery 한 건만 확인했습니다.
이 검증만으로는 첫 executor dispatch가 시작되기 전에 큐에 쌓인 전체 batch가
terminalize되는지, 모든 permit을 회수한 뒤 전체 capacity를 다시 사용할 수 있는지
증명할 수 없습니다.

## 결정

rejection과 recovery dispatch를 각각 latch로 보류하는 테스트 계약을 추가했습니다.

- 첫 dispatch가 executor에 진입한 뒤 `queueCapacity`건을 모두 admission합니다.
- rejection을 해제한 뒤 `snapshot().executorRejections`가 전체 batch 수와 같고,
  `queued`, `inFlight`, `admitted`가 모두 0인지 확인합니다.
- recovery dispatch도 실행 직전에 보류하고 동일한 `queueCapacity`건을 다시
  admission한 다음, 한 번의 worker 실행으로 모두 delivery합니다.
- 비동기 observer callback은 bounded diagnostics queue의 best-effort 경로이므로
  lossless rejection 계약은 `snapshot().executorRejections` counter로 검증합니다.
- production behavior, public API, caller-owned executor ownership은 변경하지
  않습니다.

## 결과

테스트는 한 항목만 terminalize하는 mutation에서 rejection counter가 batch 수에
도달하지 못해 실패하고, 원 구현에서는 전체 rejection과 capacity 재사용을 통과합니다.
따라서 부분 terminalization, permit 누수, recovery 실행 경쟁을 각각 재현할 수 있는
회귀 경계가 생겼습니다.

## 검증

- RED: `terminalizeQueued`를 임시로 한 항목만 처리하도록 mutation했을 때
  `executor rejection terminalizes a full queued batch and restores full capacity`
  테스트가 rejection batch 계약에서 실패했습니다.
- GREEN: 동일 테스트 focused 실행 통과.
- `./gradlew :bluetape4k-leader-core:test --rerun-tasks --no-build-cache
  --console=plain`: `BoundedLeaderAuditExporterTest` 17건 통과.
- `./gradlew :bluetape4k-leader-core:test --rerun-tasks --no-build-cache
  --console=plain`: `leader-core` 811건을 3회 연속 통과.
- `./gradlew detekt --no-configuration-cache --no-build-cache --console=plain`:
  통과.
- `git diff --check`: 통과.
- 기본 `./gradlew detekt --no-build-cache`는 기존
  `detektProductionSourceGuard` configuration-cache의 `Project` receiver 오류로
  실패했으며, 변경 코드와 무관합니다. cache를 끈 동일 검증은 통과했습니다.

## 퓨쳐 가드

bounded asynchronous pipeline의 rejection 테스트는 단일 item의 permit 회수만
검증하지 말고, dispatch handoff를 고정한 full-batch admission, lossless rejection
counter, capacity 전체 recovery를 함께 검증합니다. observer callback처럼 drop될 수
있는 진단 경로를 terminal lifecycle의 유일한 성공 신호로 사용하지 않습니다.
