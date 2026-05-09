# Implementation Plan — Leader Election State Snapshot (#68)

- **Issue**: #68 `feat: Leader Election 을 요청 전에 Leader Election 의 상태 제공`
- **작성일**: 2026-05-09
- **Spec 참조**: `docs/superpowers/specs/2026-05-09-leader-state-design.md`

---

## 0. 요약

상태 조회 공통 모델을 `leader-core`에 추가하고, 모든 elector 계열에서 `state(lockName)`를 호출할 수 있게 한다. 구현은 local 계열을 먼저 정확히 잠그고, storage backend는 이미 저장하는 owner/lockedAt/TTL 정보 또는 metadata를 이용해 best-effort 상태를 반환한다.

## Task List

### T1. Core state model 추가

**파일**

- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderStatus.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderState.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionState.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderNodeId.kt`

**작업**

- 단일 리더 상태 모델과 validation 추가
- JVM process 단위 stable `LeaderNodeId.Default` 제공
- KDoc에 best-effort snapshot 계약 명시

### T2. Options / interfaces 확장

**파일**

- `LeaderElectionOptions.kt`
- `LeaderGroupElectionOptions.kt`
- `LeaderElector.kt`
- `AsyncLeaderElector.kt`
- `VirtualThreadLeaderElector.kt`
- `SuspendLeaderElector.kt`
- `LeaderGroupState.kt`

**작업**

- options에 `nodeId` 기본값 추가
- single elector 인터페이스가 `LeaderElectionState`를 상속하도록 변경
- group state에 `leaders: List<LeaderLease> = emptyList()` 추가

### T3. Local blocking/async/virtual-thread 구현

**파일**

- `AbstractLocalLeaderElector.kt`
- `AbstractLocalLeaderGroupElector.kt`
- 관련 local elector tests

**작업**

- acquire 성공 시 lease registry 저장
- release 시 제거
- `state(lockName)` 구현
- group semaphore slot registry 추가

### T4. Local suspend 구현

**파일**

- `LocalSuspendLeaderElector.kt`
- `LocalSuspendLeaderGroupElector.kt`
- 관련 suspend tests

**작업**

- Mutex/Semaphore acquire 성공 시 lease registry 저장
- cancellation/finally 경로에서도 lease 제거
- `state(lockName)` 구현

### T5. Storage backend 상태 조회 구현

**대상**

- Exposed JDBC/R2DBC: table row 기반 조회
- MongoDB: lock document 기반 조회
- Lettuce/Redisson/Hazelcast/ZooKeeper: metadata 기반 조회 또는 holder token 기반 조회

**원칙**

- 상태 조회 실패는 leader execution 실패로 전파하지 않는다.
- 이미 `activeCount`가 있는 group backend는 leader list 조회 실패 시 count 기반 snapshot으로 degrade한다.

### T6. Tests

**필수 테스트**

- model validation
- local single 상태: empty -> occupied -> empty
- local group 상태: available slots, active leaders, release 후 empty
- suspend single/group 상태: `runTest` / existing `runSuspendIO` 패턴
- backend별 최소 happy path test 또는 기존 integration test 확장

### T7. Documentation

**파일**

- `README.md`
- `README.ko.md`
- 필요 시 module README

**작업**

- 상태 조회 사용 예시 추가
- snapshot/best-effort 의미와 lock acquire 판단에 사용하지 말라는 주의 추가

### T8. Verification / Review

**검증**

- `repo-diff`로 변경 범위 확인
- affected module tests
- 전체 build 또는 가능한 범위의 `check`
- 6-Tier code review:
  1. API/compatibility
  2. Correctness/race
  3. Backend consistency
  4. Coroutine/cancellation
  5. Tests/coverage
  6. Docs/maintainability

**PR**

- Issue #68 link
- 구현 범위, known degradation, test evidence 명시
