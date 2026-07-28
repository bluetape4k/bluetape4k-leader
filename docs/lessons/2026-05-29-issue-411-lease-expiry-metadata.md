# 강의 — Issue 411 임대 만료 메타데이터

날짜: 2026-05-29 발행: #411 분기: feat/411-lease-expiry-metadata

## 맥락

`LeaderLease` 및 여러 백엔드 상태 스냅샷은 이미 `leaseUntil`를 노출했지만 리스너 콜백 및 `LeaderElectionEvent.Elected`는 일관된 임대 스냅샷을 전달하지 않았습니다.

## 결정

소스 호환성을 위해 기존 `onElected(lockName)` 콜백을 유지하고 `onElected(lockName, leader)`를 추가합니다. 전체 선택적 임대 스냅샷을 유지하면서 `LeaderElectionEvent.Elected.fromLease()`를 사용하여 `LeaderLease.auditLeaderId` 및 `LeaderLease.leaseUntil`를 기존 `leaderId` 및 `leaseExpiry` 필드에 복사합니다.

## 결과

이제 로컬 차단 및 일시 중단 선택기가 최선의 `LeaderLease` 메타데이터를 리스너 및 수명 주기 이벤트에 전달합니다. 데코레이터 선택기는 획득 직후 대리자 상태를 읽고 대리자가 보고할 수 있을 때 메타데이터를 내보냅니다. `leaderStateFlow()`는 존재하는 경우 전체 이벤트 임대 스냅샷을 사용하고 이전 이벤트 형태에 대해서는 레거시 `leaderId`/`leaseExpiry` 필드로 대체합니다.

## 검증

- 7-Tier 검토에서는 그룹 수신기 데코레이터가 집계 `state().leaders.firstOrNull()`를 현재 슬롯 메타데이터로 잘못 보고할 수 있음을 발견했습니다. 정확한 획득 임대가 알려지지 않은 경우 그룹 데코레이터에 대해 `null`를 방출하여 수정되었습니다.
- `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.LeaderElectionEventTest" --tests "io.bluetape4k.leader.LeaderElectionListenerTest" --tests "io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest" --no-daemon` 통과: 49개 테스트.
- `git diff --check`가 통과되었습니다.
- `./gradlew build -x test -x k8sTest --no-daemon`가 통과되었습니다.
- K3s API 엔드포인트가 `localhost:34491`에 대한 연결을 거부한 후 `:bluetape4k-leader-k8s:k8sTest` 및 `:examples:k8s-operator:k8sTest`에서만 `./gradlew build -x test --no-daemon`가 failure했습니다.
- `./gradlew :bluetape4k-leader-k8s:k8sTest :examples:k8s-operator:k8sTest --no-daemon --max-workers=1`를 사용하여 K3s 레인을 순차적으로 재시도했습니다. 13개의 `leader-k8s` 테스트와 2개의 `examples:k8s-operator` 테스트를 통과했습니다.
- 7계층 수정 후 `Expected <null> to equal to "reacquired"`를 사용하는 `KubernetesLeaseSuspendLeaderElectorK3sTest.watchdog auto extends lease during long suspend action`에서 새로운 순차 K3s 레인 재실행이 한 번 failure했습니다. 즉시 재시도 시 동일한 단일 테스트가 통과되었으므로 PR 병합 전에 감시할 watchdog/K3s 수명 주기 플레이크로 기록하세요.

## 퓨쳐 가드

핵심 이벤트 메타데이터가 변경되면 콜백 API와 Flow 프로젝션 테스트를 모두 업데이트하세요. 임대 만료를 관찰 가능성 메타데이터로만 처리합니다. 소유권 결정은 백엔드 원자 획득 경로에 남아 있어야 합니다.
