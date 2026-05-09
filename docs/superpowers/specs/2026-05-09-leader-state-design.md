# Leader Election State Snapshot 설계

- **Issue**: #68 `feat: Leader Election 을 요청 전에 Leader Election 의 상태 제공`
- **작성일**: 2026-05-09
- **대상 모듈**: `leader-core`, 각 backend elector 모듈
- **상태**: Design (구현 전)

---

## 1. Background

현재 `LeaderGroupElectionState`는 `activeCount`, `availableSlots`, `state()`를 제공하지만, `LeaderGroupState`에는 실제 슬롯을 점유한 리더 정보가 없다. 단일 `LeaderElection` 계열은 선출 전 상태 조회 계약 자체가 없다.

운영 메트릭, 사전 진단, UI/API 노출을 위해 다음 스냅샷이 필요하다.

- 단일 리더: `Empty` / `Occupied`, 현재 리더 식별자, 선출 시각, lease 만료 시각
- 그룹 리더: 남은 슬롯 수 / 최대 슬롯 수, 현재 슬롯 점유 리더 목록과 선출 시각

## 2. Goals

1. 단일 리더 상태 조회를 공개 API로 추가한다.
2. 그룹 상태 모델에 현재 리더 목록을 추가한다.
3. 기존 `activeCount` / `availableSlots` 계약은 유지한다.
4. 상태 조회는 best-effort 스냅샷으로 정의한다. 조회 직후 다른 노드가 lock을 획득/해제할 수 있다.
5. lock 획득 실패 여부 판단에는 상태 API를 사용하지 않는다. 기존 원자적 acquire 경로가 authoritative source다.

## 3. Non-goals

- 리더 선출 이력 저장 및 조회. 이력은 향후 `leader-micrometer` / history 이슈에서 다룬다.
- 상태 조회를 분산 consensus read로 보장하지 않는다.
- lease 연장/최소 lease 보장 로직은 #38/#77에서 다룬다.

## 4. Public API

### 4.1 `LeaderStatus`

```kotlin
enum class LeaderStatus {
    Empty,
    Occupied,
}
```

단일 리더 상태를 명시한다. enum 이름은 Kotlin 스타일과 KDoc 가독성을 위해 PascalCase를 사용한다.

### 4.2 `LeaderLease`

```kotlin
data class LeaderLease(
    val leaderId: String,
    val electedAt: Instant? = null,
    val leaseUntil: Instant? = null,
    val slot: Int? = null,
) : Serializable
```

- `leaderId`: 가능한 경우 사용자/노드 식별자. backend가 token만 보유하는 경우 fencing token 또는 backend holder id를 사용한다.
- `electedAt`: lock 획득 시각. backend가 정확한 시각을 보유하지 않으면 `null`.
- `leaseUntil`: lease 만료 시각. local 구현처럼 자동 TTL이 없는 경우 `electedAt + leaseTime` 근사값을 사용한다.
- `slot`: 그룹 리더 슬롯 번호. 단일 리더는 `null`.

### 4.3 `LeaderState`

```kotlin
data class LeaderState(
    val lockName: String,
    val status: LeaderStatus,
    val leader: LeaderLease? = null,
) : Serializable {
    val isEmpty: Boolean get() = status == LeaderStatus.Empty
    val isOccupied: Boolean get() = status == LeaderStatus.Occupied
}
```

`status == Empty`이면 `leader == null`이어야 한다. `status == Occupied`이면 `leader != null`이어야 한다.

### 4.4 `LeaderElectionState`

```kotlin
interface LeaderElectionState {
    fun state(lockName: String): LeaderState
}
```

`LeaderElector`, `AsyncLeaderElector`, `VirtualThreadLeaderElector`, `SuspendLeaderElector`는 이 인터페이스를 상속한다. `LeaderElector`가 `AsyncLeaderElector`를 상속하므로 동기/비동기 구현체는 하나의 `state()` 구현을 공유한다.

### 4.5 `LeaderGroupState` 확장

기존 생성자 호환성을 보존하기 위해 `leaders`를 기본값이 있는 마지막 파라미터로 추가한다.

```kotlin
data class LeaderGroupState(
    val lockName: String,
    val maxLeaders: Int,
    val activeCount: Int,
    val leaders: List<LeaderLease> = emptyList(),
) : Serializable
```

검증 규칙:

- `leaders.size <= maxLeaders`
- `activeCount`는 기존처럼 `0..maxLeaders`
- backend가 현재 리더 목록을 제공할 수 있으면 `activeCount == leaders.size`
- backend가 리더 목록을 아직 제공하지 못하는 transitional 구현에서는 `leaders`가 빈 목록일 수 있다. 이 경우 `activeCount`가 authoritative 값이다.

## 5. Backend State Strategy

### 5.1 Local

`AbstractLocalLeaderElector`, `LocalSuspendLeaderElector`, `AbstractLocalLeaderGroupElector`, `LocalSuspendLeaderGroupElector`에 in-memory lease registry를 둔다.

- acquire 성공 직후 `LeaderLease(leaderId = options.nodeId, electedAt = now, leaseUntil = now + leaseTime)` 저장
- action 완료 후 unlock/release 직전에 제거
- group은 slot 개념이 없는 semaphore 기반이므로 `slot`은 acquire 순번 기반 local slot id를 할당한다.

`LeaderElectionOptions`와 `LeaderGroupElectionOptions`에 `nodeId`를 추가하되 기본값은 JVM 프로세스 단위 stable id로 둔다. 랜덤값을 생성자 기본값에서 매번 만들지 않는다.

### 5.2 Exposed JDBC/R2DBC

`LeaderLockTable`, `LeaderGroupLockTable`은 이미 `lockOwner`, `lockedAt`, `lockedUntil`, `slot`을 보유한다. 상태 조회는 만료되지 않은 row를 읽어 `LeaderLease`로 매핑한다.

### 5.3 MongoDB

lock document에 기존 `token`, `expireAt`가 있다. `tryLock` 성공 시 `owner`, `lockedAt` 필드를 함께 저장하고, 상태 조회는 `_id` / `expireAt > now` 조건으로 읽는다.

### 5.4 Lettuce / Hazelcast / ZooKeeper / Redisson

각 저장소가 현재 token 또는 holder id를 보유한다. 정확한 `electedAt`이 없으면 #68 구현에서 별도 metadata key/map/node를 함께 기록한다. metadata는 lock lease와 같은 TTL로 설정하고 unlock 시 삭제한다.

상태 조회 실패는 leader 실행을 막지 않는다. backend 예외는 기존 상태 API 관례처럼 warn 로그 후 empty snapshot 또는 count 기반 snapshot으로 degrade한다.

## 6. Compatibility

- `LeaderGroupState(lockName, maxLeaders, activeCount)` 호출은 `leaders` 기본값으로 계속 컴파일된다.
- `LeaderElector` 계열에 `state()`가 추가되므로 외부 구현체는 소스 호환 수정이 필요하다. 현재 저장소의 구현체는 모두 업데이트한다.
- `LeaderElectionOptions` / `LeaderGroupElectionOptions`에 `nodeId` 기본값을 추가한다. 기존 positional constructor 호출은 유지된다.

## 7. Testing

- core model validation tests
- local single/group blocking/async/virtual-thread state tests
- local suspend single/group state tests
- backend별 상태 조회 unit/integration tests는 저장소 접근 비용에 맞춰 최소 하나 이상의 happy path + release 후 empty path를 둔다.

## 8. Review Notes

Spec review 결과:

- API: 상태는 lock 획득의 근거가 아니라 관측 스냅샷이어야 한다.
- Compatibility: `LeaderGroupState` 확장은 마지막 기본값 파라미터로 제한한다.
- Observability: `LeaderLease`는 `electedAt`과 `leaseUntil`을 모두 가져야 이후 Prometheus export에서 gauge/label 계산이 가능하다.
- Backend: 정확한 owner를 제공하지 못하는 backend는 token을 `leaderId`로 노출하되 README/KDoc에서 best-effort라고 명시한다.
