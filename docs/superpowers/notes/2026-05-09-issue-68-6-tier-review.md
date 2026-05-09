# 6-Tier Code Review — Issue #68 Leader State Snapshots

- **Issue**: #68
- **작성일**: 2026-05-09
- **범위**: `leader-core` public state API, local blocking/async/virtual-thread/suspend state registry, docs/tests

## 1. API / Compatibility

- `LeaderElectionState`를 추가하고 single elector 인터페이스가 이를 상속한다.
- 외부 구현체 소스 호환성을 위해 `state(lockName)` 기본 구현은 empty snapshot을 반환한다.
- `LeaderElectionOptions` / `LeaderGroupElectionOptions`에 기본값 있는 `nodeId`를 마지막 파라미터로 추가해 기존 positional 호출을 유지한다.
- `LeaderGroupState.leaders`도 기본값 있는 마지막 파라미터로 추가했다.

**Risk**: non-local backend single elector는 실제 holder metadata를 override하지 않으면 empty snapshot을 반환한다. 이 PR은 API와 local 정확성 수직 경로를 먼저 제공한다.

## 2. Correctness / Race

- local single은 acquire 성공 직후 lease를 등록하고 finally에서 제거한다.
- local group은 semaphore acquire 성공 후 registry에 slot lease를 등록하고 finally에서 제거한다.
- 상태 조회는 lock acquire primitive가 아니라 best-effort snapshot으로 문서화했다.

**Risk**: local group slot은 저장소 슬롯이 아닌 JVM-local 관측 slot이다.

## 3. Backend Consistency

- 기존 group backend의 `activeCount` / `availableSlots` 동작은 유지된다.
- `LeaderGroupState.leaders`는 backend가 leader list를 채우지 않으면 빈 목록일 수 있도록 계약화했다.

**Risk**: Redis/Mongo/Hazelcast/ZooKeeper/Exposed single backend의 정확한 `Occupied` 구현은 후속 backend metadata 작업이 필요하다.

## 4. Coroutine / Cancellation

- suspend single/group 모두 acquire 후 registry 등록, `finally`에서 registry 제거와 unlock/release를 수행한다.
- 기존 cancellation propagation 경로를 바꾸지 않았다.

## 5. Tests / Coverage

- `LeaderStateTest`로 model validation을 추가했다.
- `LocalLeaderElectionTest`에 occupied -> empty 상태 전이를 추가했다.
- `LocalLeaderGroupElectionTest`에 active leaders list 검증을 추가했다.
- `:leader-core:test`: 288 passing
- `:leader-core:koverXmlReport`: line coverage 565/642 = 88.0%
- `compileKotlin`: 전체 모듈 compile 통과

## 6. Docs / Maintainability

- root README / Korean README에 state snapshot 사용 예와 best-effort 경고를 추가했다.
- `leader-core` README / Korean README에 single/group state API 예시를 추가했다.
- 새 public API에는 KDoc을 추가했다.

## Verdict

PR 생성 가능. 단, #68의 전체 backend 정확성까지 한 PR에서 닫으려면 backend별 holder metadata override가 추가로 필요하다. 현재 PR은 core API와 local 구현을 안전하게 제공하는 리뷰 단위로 제한한다.
