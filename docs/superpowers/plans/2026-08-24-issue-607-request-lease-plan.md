# Issue #607 요청별 lease 획득·연장·해제 구현 계획

## 결과 목표와 종료 조건

Issue #607의 승인된 option 2를 구현한다. `leader-core`에 additive request-lease
capability를 추가하고, 모든 publishable single-leader backend가 동일한 acquire →
watchdog → minimum lease → conditional release lifecycle을 사용하도록 정리한다.
`leader-spring-boot`에는 기본 비활성인 `authority-mode=LEASE`를 추가하여 MVC와
WebFlux 요청 전체를 하나의 lease handle로 보호한다. 기존 `LeaderElector`,
`STATE`/`CUSTOM` route guard, redirect 정책, `LeaderLease` 상태 데이터는
호환성을 유지한다.

구현 종료 조건은 다음을 모두 만족하는 경우다.

1. 승인된 설계의 acceptance criteria와 요구사항 추적성 표가 코드·테스트·문서에
   매핑되고, 구현 단계별 RED→GREEN 증거가 남는다.
2. core capability와 local backend contract가 통과한 뒤 backend matrix의 모든
   publishable single-leader 구현 및 capability-preserving wrapper가 통과한다.
3. MVC/WebFlux의 성공·contention·exception·timeout·cancellation·cross-thread
   completion이 동일한 rejection, ownership, cleanup 계약을 지킨다.
4. queue/admission, residual, fencing, watchdog, monotonic deadline, fixed
   observability schema가 bounded 상태로 검증된다.
5. public JVM descriptor, serialization, binary compatibility, AOT, detekt,
   EN/KO README/KDoc와 pinned manual 경계가 검증된다.
6. 독립 코드 리뷰와 최종 CI에서 P0/P1 이슈가 없고, PR은 fresh exact-head
   evidence로 생성한다. merge는 별도의 명시적 승인 뒤에만 수행한다.

구현하지 않는 항목은 `LeaderGroupElector`/`Strategic*`의 semaphore 또는 scoring
lease, #606 redirect URI 정책 변경, 신규 dependency/module/BOM/workflow, request별
arbitrary TTL override, response rollback/강제 중단이다.

## 구현 전 불변 계약

- branch/worktree: `feat/epic/spring-r-02-request-lease` / 현재 격리 worktree
- base: `origin/develop` 및 predecessor exact head
  `56a33db44e22fb137e205119dd853f153cff3402`
- public API: `LeaderLeaseAcquirer`, `LeaderLeaseHandle`은
  `io.bluetape4k.leader`, suspend API는
  `io.bluetape4k.leader.coroutines`에 additive로 추가한다.
- 정상 contention은 `tryAcquire(...) == null`이며 예외가 아니다.
- lock-name overload는 startup에서 캡처한 `configuredOptions.copy().nodeId`를
  audit identity로 사용하고, `LeaderSlot` overload는 caller의 `leaderId`를
  그대로 보존한다.
- `LeaderLease`의 FQN, fields, serialization, listener/event surface와 기존
  `LeaderElector`/route `STATE`/`CUSTOM` source·binary surface는 유지한다.
- handle은 token/delegate/backend address를 공개하지 않는다. `release`/`close`는
  cross-thread·반복 호출에 idempotent하고 backend release/listener revoke는
  handle당 최대 한 번이다.
- `LeaseOwnershipStatus`는 `HELD`, `NOT_HELD`, `UNKNOWN`만 사용한다.
  ordinary release failure, timeout, residual은 `UNKNOWN`; fencing mismatch와
  명시적 not-held는 `NOT_HELD`다. `isStillHeld()`는 `HELD`일 때만 true다.
- handle state는 `LIVE → CLOSING → CLOSED`, private holder state는
  `ACQUIRING/CANCELLED/PUBLISHED/LIVE/CLOSING/CLOSED/CLOSED_WITH_LEAKS`다.
  state owner는 request/exchange factory, lifecycle helper, shutdown coordinator로
  분리하고 public handle에는 private holder state를 노출하지 않는다.
- acquire는 backend 호출 전
  `tryReserve(maxConcurrentAcquires)` → `tryReserve(maxAcquireQueueDepth)`를
  순서대로 수행한다. shared attempt의 canonical tuple은
  `(acquireAttemptPermit, acquireQueueSlot, cleanupReservation, residualSlot)`이며,
  joiner는 waiter reference만 갖는다. success CAS에서 cleanup tuple을 holder로
  transfer하고 attempt/queue permit은 callback terminal에서 정확히 한 번 반환한다.
- release는 watchdog 중단 → `minLeaseTime` 대기 → fencing-token conditional
  release → revoke event 순서다. queue drain 또는 backend deadline이 넘으면
  `LeaseCleanupBoundary.releaseWithin(...)`이 `RESIDUAL_TRANSFERRED`를 반환하고
  process-scoped `ResidualLeaseRegistry`가 reservation을 보유한다.
- public no-arg release deadline은
  `nowMonotonic + min(30s, configuredOptionsBaseline.leaseTime)`이다. route/context
  cleanup은 request/close deadline을 우선하고 wall clock이 아닌 monotonic 기준을
  사용한다. suspend cleanup/rollback은 `NonCancellable`에서 수행하며 원래
  cancellation/interruption/fatal `Error`를 숨기지 않는다.
- `effectiveActiveCapacity = min(maxActiveLeases, maxResidualLeases)`이며
  `activeWatchdogs <= activeLeases <= effectiveActiveCapacity`와 bounded queue/
  cleanup in-flight invariant를 항상 지킨다.
- `authority-mode=LEASE`는 `route-guard.enabled=true`에서만 생성된다. sync MVC와
  non-suspend WebFlux는 `LeaderLeaseAcquirer`, native suspend WebFlux는
  `SuspendLeaderLeaseAcquirer`를 정적 capability로 선택한다. `runBlocking`이나
  sync→suspend 임의 cast는 금지한다. redirect와 `LEASE` 조합은
  `LEADER_ROUTE_LEASE_REDIRECT_INCOMPATIBLE`로 fail-fast한다.
- raw token, backend address, leader identity, exception class/message는 response,
  log, metric, event public surface에 기록하지 않는다. observation code/tag는
  고정 allow-list만 사용하며 `framework ∈ {mvc, webflux, core}`다.
- 현재 `docs/manual/manifest.yaml`의 pinned `releaseRef=0.5.0`에는 새 API claim을
  추가하지 않는다. 1.0.0 pin 이후 EN/KO manual 반영은 후속 release 작업이다.

## 작업 순서와 TDD 증거

### 1. 계획·계약·공통 시간/관찰 primitive 고정

대상 파일:

- `docs/superpowers/specs/2026-08-23-issue-607-request-lease-design.md`
- 본 계획 파일
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaseOwnershipStatus.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaseCleanupBoundary.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaseCleanupResult.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaseCleanupReservation.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseDefaults.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAcquirer.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseHandle.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderLeaseAcquirer.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderLeaseHandle.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt`

작업:

1. 먼저 public contract test를 작성한다. JVM 시그니처는 `kotlin.time.Duration`,
   `java.time.Instant`, `AutoCloseable`, `Serializable` 경계를 reflection/API
   test로 고정한다. `LeaderLease`는 수정하지 않고 serialization 회귀 fixture를
   추가한다.
2. 다음 public method surface를 정확히 구현하고 API dump로 고정한다.

   ```kotlin
   interface LeaderLeaseAcquirer {
       val configuredOptions: LeaderElectionOptions
       fun tryAcquire(lockName: String): LeaderLeaseHandle?
       fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle?
   }

   interface LeaderLeaseHandle : AutoCloseable {
       val lockName: String
       val auditLeaderId: String
       val acquiredAt: Instant
       fun extend(lockAtMostFor: Duration): ExtendOutcome
       fun ownershipStatus(): LeaseOwnershipStatus
       fun isStillHeld(): Boolean
       fun release()
       override fun close() = release()
   }
   ```

   suspend surface는 같은 property와 `suspend` method descriptor를
   `SuspendLeaderLeaseAcquirer`/`SuspendLeaderLeaseHandle`에 적용한다. handle은
   `Serializable`이 아니며 backend token/delegate를 public property로 두지 않는다.
3. `LeaseOwnershipStatus`, `ExtendOutcome.Rejected`, cleanup result와 defaults를
   additive로 구현한다. public handle은 raw backend state를 반환하지 않고 고정
   observation sink에만 sanitized 결과를 전달한다.
4. sync/suspend acquirer overload 두 개를 모두 abstract/direct implementation으로
   둔다. interface default bridge나 lock-name-only fallback을 만들지 않는다. 각
   built-in/custom capability 구현이 직접 `LeaderSlot(lockName,
   configuredOptionsBaseline.nodeId)`를 생성해 lock-name overload를 정규화하고,
   slot overload는 caller slot을 그대로 보존한다.
5. `LeaseCleanupBoundary` 입력은 monotonic deadline과
   `(cleanupReservation, residualSlot)` composite를 함께 받으며 결과를
   `RELEASED`, `NOT_HELD`, `RESIDUAL_TRANSFERRED` 세 값으로 제한한다.

TDD 증거:

- RED: 신규 `LeaderLeaseApiContractTest`, `LeaderLeaseSerializationContractTest`,
  `LeaseCleanupBoundaryContractTest`가 compile/API와 idempotence에서 실패한다.
- GREEN: additive API와 기본값 구현 후 core test가 통과한다. suspend handle은
  `AutoCloseable`을 구현하지 않고 suspend `release()`만 제공하는지 확인한다.
- `./gradlew --no-daemon :bluetape4k-leader-core:test --tests '*Lease*ContractTest'`

   API/ABI test는 다음 descriptor를 생략 없이 고정한다.

   ```text
   # LeaderRouteGuardProperties legacy/new constructors and copy methods
   ()V
   (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
   (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
   (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;)V
   copy(ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
   copy$default(Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;ILjava/lang/Object;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
   (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;)V
   copy(ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
   copy$default(Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;ILjava/lang/Object;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
   (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;)V
   (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
   copy(ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
   copy$default(Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;ILjava/lang/Object;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
   # LeaderRouteLeaseProperties
   ()V
   (Ljava/time/Duration;IIIIIIIILjava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;)V
   (Ljava/time/Duration;IIIIIIIILjava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
   ```

   Kotlin inline `Duration` methods are recorded with the exact compiler-mangled
   `extend` name emitted by `javap` in the API contract fixture; `tryAcquire(String)`
   and `tryAcquire(LeaderSlot)` have no inline-`Duration` mangling. The expected
   semantic descriptors are `tryAcquire(String): LeaderLeaseHandle`,
   `tryAcquire(LeaderSlot): LeaderLeaseHandle`, `extend(long): ExtendOutcome`,
   suspend `tryAcquire(String, Continuation): Object`, suspend
   `tryAcquire(LeaderSlot, Continuation): Object`, suspend
   `extend(long, Continuation): Object`, and suspend `release(Continuation): Object`.
   suspend `ownershipStatus(Continuation): Object`, suspend
   `isStillHeld(Continuation): Object`. The exact expected public method/property set
   also includes `LeaderLeaseAcquirer.getConfiguredOptions(): LeaderElectionOptions`,
   sync handle getters `getLockName(): String`, `getAuditLeaderId(): String`,
   `getAcquiredAt(): Instant`, `ownershipStatus(): LeaseOwnershipStatus`,
   `isStillHeld(): boolean`, `extend(long): ExtendOutcome`, `release(): void`,
   `close(): void`. Suspend ABI also fixes
   `SuspendLeaderLeaseAcquirer.getConfiguredOptions(): LeaderElectionOptions`,
   suspend handle getters `getLockName(): String`, `getAuditLeaderId(): String`,
   `getAcquiredAt(): Instant`, and the suspend semantic descriptors
   `ownershipStatus(Continuation): Object`, `isStillHeld(Continuation): Object`,
   `extend(long, Continuation): Object`, `release(Continuation): Object`.
   The test fails if compiler-generated names or any parameter/return descriptor
   drifts.

### 2. core lifecycle, admission, holder handoff, residual registry

대상 파일:

- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaderLeaseLifecycle.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/SuspendLeaderLeaseLifecycle.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaseAdmissionController.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/SharedLeaseAcquire.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaseCleanupBoundaryImpl.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/ResidualLeaseRegistry.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaseOperationScheduler.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaseBackendCallbacks.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/MinLeaseTimeSupport.kt`
- 기존 `LockStateHolder`, `LeaderLockHandle`, context capture 파일

작업:

1. acquire callback 계약을 `LeaderSlot`, absolute monotonic wait/transport
   deadline, private token/generation을 받도록 만든다. null contention과
   `AcquireDeadlineSignal`은 handle 생성 없이 terminalize한다.
2. shared acquire factory는 attempt/queue permit과 composite cleanup reservation을
   backend 호출 전에 reserve한다. `ACQUIRING` install CAS에서 패배한 호출은
   backend를 호출하지 않고 자원을 반환한다. duplicate는 같은 future에 join하고
   마지막 waiter 취소 때만 shared attempt를 취소한다.
3. 성공 callback의 `ACQUIRING → PUBLISHED` CAS에서 cleanup reservation/residual slot을
   새 handle로 원자 transfer한다. late callback은 동일 reservation으로
   `NonCancellable` conditional discard/release를 마친 뒤에만 회수한다.
4. handle lifecycle은 bounded serial executor로 extend/release를 선형화한다.
   `CLOSING/CLOSED` extend는 backend를 호출하지 않고 `NotHeld`, queue saturation은
   `Rejected`다. self-join을 피하고 release winner만 conditional release를 호출한다.
5. release failure/timeout, watchdog stop failure, scheduler exhaustion을
   `ResidualLeaseRegistry`로 transfer한다. registry는 application/process scope에서
   `maxResidualLeases`와 quarantine retention을 관리하며 terminal proof 전에는
   reservation을 pool에 반환하지 않는다. 이미 reservation을 보유한 residual의
   terminal transfer/reconciliation은 cap을 다시 검사하지 않고 반드시 성공해야
   하며, 이 경로가 예기치 않게 실패하거나 cap invariant가 깨지면 public error를
   새로 만들지 않고 `LeaseObservationCode.BACKEND_ERROR`로만 sanitized fatal
   observation을 남긴다. 신규 acquire와 reservation 없는 late handoff는 기존
   `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 fail-closed한다.
6. `LeaderLeaseAutoExtender`에 required-start, bounded watchdog admission,
   token/generation, absolute deadline, stop outcome을 연결한다. 기존 action API의
   `LeaderLockHandle` context push/pop은 유지하고 public request handle과 ownership을
   겹쳐 소유하지 않는다.

TDD 증거:

- RED: `LeaderLeaseLifecycleTest`, `LeaseAdmissionControllerTest`,
  `SharedLeaseAcquireStressTest`, `ResidualLeaseRegistryTest`,
  `LeaseCleanupBoundaryTest`를 먼저 작성한다.
- GREEN: deterministic fake callback과 `TestTimeSource`로 success, contention,
  cancellation, deadline, holder-install race, late-acquire, close race,
  stale-handle fencing, queue flood, residual quarantine를 통과시킨다. flood와
  cancellation storm은 waiter 수 `1`, `10`, `1000`의 scaling probe로 실행한다.
  `ResidualLeaseRegistryTest`는 cap가 이미 찬 상태에서 기존 reservation의
  terminal transfer가 성공하는지와 reservation 없는 신규 residual이 기존
  `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 fail-closed되고
  `LeaseObservationCode.BACKEND_ERROR`만 내부적으로 기록되는지를 분리해 검증한다.
  shared-attempt key/map entry는 terminal callback 뒤 baseline size로 회수되고,
  duplicate join은 key/map scan 없이 O(1) waiter reference만 추가하며,
  backend call은 race당 0/1회여야 한다. per-handle physical scheduler/thread는
  0개를 생성하고 shared bounded physical scheduler instance는 각 framework당
  1개만 사용한다. handle별 logical serial lane은 이 shared executor 위에서만
  FIFO/self-join 방지를 제공하며, physical scheduler count와 logical lane count를
  별도 측정한다. resource counter와 registry size가 baseline으로 돌아오는지
  검증한다.
- `LeaderLeaseWatchdogAdmissionTest`와 `LeaderLeaseWatchdogStressTest`는
  `maxWatchdogInFlight` 포화/거부, scheduler rejection rollback, maxLeaseLifetime
  종료, N=1000 active handles의 timer/task 수와 O(N) full-scan 금지를
  deterministic clock과 allocation/task counters로 검증한다. pass 식은
  `sharedSchedulerInstances=1`, `scheduledTimerTasks<=activeHandles`,
  `extensionQueue<=maxWatchdogInFlight`, `watchdogInFlight<=maxWatchdogInFlight`,
  `activeWatchdogs<=activeLeases<=effectiveActiveCapacity`,
  `maxLeaseLifetime` 이후 신규 extension task=0으로 고정한다.
- cross-thread/concurrent 검증은 `MultithreadingTester` 또는 existing core stress
  fixture를 사용하고, suspend 경로는 `runTest` + cancellation test를 사용한다.
- `./gradlew --no-daemon :bluetape4k-leader-core:test`

### 3. local backend 및 core action parity

대상 파일:

- `leader-core/src/main/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderElector.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElector.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalAsyncLeaderElector.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalVirtualThreadLeaderElector.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LocalSuspendLeaderElector.kt`
- local factories와 `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/`

작업:

1. local registry는 `lockName`만으로 unlock하지 않고 acquisition generation을
   비교하는 conditional primitive를 제공한다. completion thread가 달라도 release할
   수 있도록 `ReentrantLock.unlock()` 직접 호출을 request path에서 제거한다.
2. `AbstractLocalLeaderElector`에 sync/suspend capability를 직접 구현하고 기존
   `runIfLeader`는 공통 lifecycle helper를 호출하도록 바꾼다. listener elected/skipped/
   revoked와 watchdog/min lease는 helper 한 곳에서만 발생한다.
3. async/virtual wrapper는 sync acquirer를 executor boundary에서 위임하고, suspend는
   suspend lifecycle을 사용한다. `runBlocking` bridge는 추가하지 않는다.
4. contract fixture에 lock-name default identity, slot identity, normal contention,
   cross-thread close, stale handle, extend/release race와 event exactly-once를 넣는다.

TDD 증거:

- RED: `LocalLeaderLeaseAcquirerTest`, `LocalSuspendLeaderLeaseAcquirerTest`,
  `LocalLeaderElectorParityContractTest`.
- GREEN: local deterministic lifecycle와 기존 action regression을 통과시킨다.
- `./gradlew --no-daemon :bluetape4k-leader-core:test`

### 4. publishable backend capability migration

대상 범위와 소유 파일:

- Redis: `leader-redis-lettuce`, `leader-redis-redisson`의 sync/suspend elector,
  lock/extend delegate, factory, contract/integration test
- SQL: `leader-exposed-jdbc`, `leader-exposed-r2dbc`, `leader-exposed-core`의
  elector/delegate/factory/test
- document/cloud: `leader-mongodb`, `leader-dynamodb`의 elector/delegate/test
- coordination: `leader-etcd`, `leader-consul`, `leader-zookeeper`의 elector/delegate/test
- platform/cache: `leader-k8s`, `leader-hazelcast`의 elector/delegate/test
- wrappers: `leader-core`의 `ListeningLeaderElectors.kt`,
  `coroutines/ListeningSuspendLeaderElectors.kt`,
  `TenantScopedLeaderElectors.kt`, `coroutines/TenantScopedSuspendLeaderElectors.kt`,
  `leader-micrometer/src/main/kotlin/**/InstrumentedLeaderElectors.kt`

작업 순서:

1. 각 backend의 현재 acquire/extend/release delegate를 callback table
   `acquire/extend/release/isHeld/stopWatchdog`로 매핑한다. 기존 backend-specific
   locking primitive는 재사용하고 reflection/new generic backend abstraction은
   만들지 않는다.
2. callback마다 acquisition token/generation과 monotonic transport deadline을
   전달한다. capability를 제공하지 못하는 custom implementation은
   `LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED`로 selector에서 거부한다. fencing
   token/conditional release, backend TTL 또는 bounded retention 중 하나라도
   보장하지 못하는 backend는 부분 구현으로 노출하지 않고 같은 code로 제외하며,
   capability preflight가 이 세 보장을 각각 증명하는지 contract test로 고정한다.
3. Redisson은 owner field/TTL/fencing token 생성·설정을 한 번의 Lua `EVAL`로 처리하고,
   extend/unlock/min-lease cleanup도 token/version compare Lua로 원자화한다.
4. Lettuce, Exposed, MongoDB, DynamoDB, etcd, Consul, ZooKeeper, Kubernetes,
   Hazelcast는 built-in capability를 구현한다. sync/suspend/async/virtual 각 surface의
   deadline, cancellation, cross-thread release를 공통 fixture로 검증한다.
5. listener/micrometer/tenant wrapper는 capability-preserving adapter를 명시적으로
   붙이고 lifecycle/event/watchdog를 새로 만들지 않는다. capability 없는 delegate는
   기존 elector view만 노출한다. duplicate acquire/revoke/metric을 0회로 검증한다.
6. group/strategic elector는 capability를 추가하지 않고 기존 contract regression만
   실행한다.

TDD 증거:

- RED/GREEN은 backend별 disjoint commit에서 수행한다. core/local contract가 green인
  뒤 Redis → SQL → document/cloud → coordination → platform/cache 순으로 진행한다.
- 각 backend는 기존 `*LeaderElectorIntegrationTest`, `*SuspendLeaderElector*Test`,
  Testcontainers contract를 확장한다. container unavailable/skip은 성공으로 세지
  않고 원인과 잔여 위험을 기록한다.
- 대표 명령 예시는 다음과 같다.
  `./gradlew --no-daemon :bluetape4k-leader-redis-lettuce:test`
  `./gradlew --no-daemon :bluetape4k-leader-redis-redisson:test`
  `./gradlew --no-daemon :bluetape4k-leader-exposed-jdbc:test :bluetape4k-leader-exposed-r2dbc:test`
  변경된 backend의 기존 `test` task와 공통 contract task를 모두 실행한다.

### 5. Spring properties, selector, diagnostics와 lifecycle runtime

대상 파일:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteAuthority.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteGuardAutoConfiguration.kt`
- 신규 `spring/route/lease/LeaderRouteLeaseRuntime.kt`, `LeaseCapabilityResolver.kt`,
  `LeaseObservationCode.kt`, `SanitizedRouteLeaseObservationSink.kt`,
  `LeaderRouteLeaseShutdownCoordinator.kt`, `LeaderRouteLeaseMetrics.kt`,
  `LeaderRouteLeaseDiagnosticsContributor.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusRegistry.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpoint.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendDiagnosticsEndpoint.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfiguration.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendDiagnosticsActuatorAutoConfiguration.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderMicrometerAutoConfiguration.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnostics.kt`
- `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`

작업:

1. `LeaderRouteAuthorityMode.LEASE`를 additive enum으로 추가하고 기존
   `LeaderRouteGuardProperties`의 4/5-argument constructor, `copy`/`copy$default`,
   default constructor와 `readResolve`를 유지한다. 새 lease field는 마지막
   overload와 legacy default로 추가한다.
2. `LeaderRouteLeaseProperties`를 `Serializable data class`로 추가한다. 기본값과
   범위는 spec의 13개 key(`max-blocking-wait-time`부터 `drain-timeout`)를 고정하고,
   `maxMvcBlockingAcquires <= maxConcurrentAcquires`, positive queue depth,
   `maxResidualLeases <= maxActiveLeases`, option/TTL/extension-latency 관계를
   startup에서 검증한다. `effectiveActiveCapacity`는 bindable property가 아니다.

   | property | JVM type | default |
   |---|---|---:|
   | `maxBlockingWaitTime` | `java.time.Duration` | `5s` |
   | `maxConcurrentAcquires` | `Int` | `256` |
   | `maxConcurrentCleanups` | `Int` | `256` |
   | `maxAcquireQueueDepth` | `Int` | `1024` |
   | `maxCleanupQueueDepth` | `Int` | `1024` |
   | `maxMvcBlockingAcquires` | `Int` | `32` |
   | `maxActiveLeases` | `Int` | `10000` |
   | `maxResidualLeases` | `Int` | `1024` |
   | `maxWatchdogInFlight` | `Int` | `256` |
   | `maxLeaseLifetime` | `java.time.Duration` | `10m` |
   | `minimumAutoExtendLeaseTime` | `java.time.Duration` | `100ms` |
   | `maxExpectedExtensionLatency` | `java.time.Duration` | `50ms` |
   | `drainTimeout` | `java.time.Duration` | `30s` |

   `serialVersionUID=1L`, public no-arg/default constructor, `copy`/`copy$default`,
   legacy `readResolve`, positive finite range와 `1..65536` queue bound를 모두
   serialization/binding test로 고정한다.
3. selector는 정확히 하나의 elector bean을 선택한 뒤 sync/suspend capability를
   각각 정적으로 확인한다. missing/ambiguous/wrong-type error code는 기존 것을
   유지하고 unsupported code만 lease 전용으로 추가한다. selected capability의
   `configuredOptions.copy()`를 한 번 캡처해 모든 request/handle이 사용한다.
4. `LEASE + redirect.enabled`를 stable incompatible error로 거부하고, enabled=false/
   STATE에서는 lease semantic validation/bean creation을 수행하지 않는다. LEASE
   semantic validator가 redirect bean 생성보다 먼저 실행되며, malformed redirect와
   invalid lease value가 disabled/STATE rollback에서 startup을 막지 않고,
   enabled LEASE에서는 generic binding error보다 stable lease/redirect error code로
   수렴하는 ordering test를 둔다.
5. core-only aggregate diagnostics와 고정 observation allow-list를 wiring한다.
   `leaderRouteLease` Actuator contributor의 `runtimeState`, core-only `active`/
   `effectiveActiveCapacity`, queue/cleanup/watchdog/unknown/residual field와 context
   reset/terminal retention을 고정한다. 기존 `LeaderElectionStatusRegistry`와
   `LeaderElectionStatusEndpoint`는 route lease aggregate만 읽고 dynamic lock identity,
   raw exception, token, backend URI, leader identity를 새 status/meter tag에 넣지
   않도록 수정한다. 새 contributor는 위 observability auto-configuration에서
   조건부 bean/endpoint로 연결한다. 기존 `LeaderElectionStatusEndpoint`의 backend,
   bean, locks, leaderId legacy payload와 raw listener semantics는 그대로 보존하고,
   별도 `leaderRouteLease` contributor만 sanitized aggregate를 반환한다. Micrometer
   부재 시에는 bounded in-memory aggregate와 startup warning만 사용하고 unbounded
   registry를 만들지 않는다.
6. shutdown coordinator는 신규 acquire 차단 → active request/watchdog/cleanup drain →
   Spring이 소유한 bounded `acquireScheduler`/`cleanupScheduler`와 executor dispose
   순서를 관리한다. 기존 factory의 caller-provided `evaluationScheduler` 인자는
   compatibility bridge로 acquire 경계에만 검증·매핑하고 blocking cleanup에 사용하지
   않으며, caller-owned 입력을 factory가 dispose하지 않는다. auto-configuration이
   생성한 dedicated scheduler는 context shutdown에서만 drain 후 dispose한다.
   residual registry process scope는 scheduler dispose와 독립적으로 유지한다.
   cleanup submission은 최초 시도 뒤 backend release 시작 전 scheduler/worker 실패에
   한해 최대 두 번만 `0ms`, `100ms` backoff로 재제출한다(총 submission 3회).
   backend release가 시작된 뒤에는 hidden retry를 하지 않는다. timeout은
   `LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT`과 `LEADER_ROUTE_LEASE_DRAIN_TIMEOUT`을
   함께 기록하고 `CLOSED_WITH_LEAKS`/fixed metric으로 남기며 `DRAINED`로 위장하지
   않는다.

   `ResidualLeaseRegistry` entry는
   `min(acquiredAtMonotonic + maxLeaseLifetime + drainTimeout,
   transferAtMonotonic + drainTimeout)` retention deadline, origin context generation,
   fencing/TTL proof와 terminal outcome을 보유한다. proof가 없으면
   `QUARANTINED_UNKNOWN`으로 고정하고 `drain.residual.expired{reason=unknown}`을
   증가시키며 slot/eviction을 금지한다. proof가 회복될 때만
   `QUARANTINED_UNKNOWN → EVICTED`를 한 번 수행하고
   `reason=quarantined`와 residual counter를 갱신한다. `maxResidualLeases` 포화의
   reservation 없는 신규 acquire 또는 신규 residual 생성만 backend call 전에
   fail-closed하고 `drain.residual.rejected`를 기록한다. 이미
   `(cleanupReservation, residualSlot)`을 보유한 late callback/handoff는 cap을
   재검사하지 않고 terminal transfer/reconciliation에 반드시 진입한다. reservation
   없는 late callback/handoff는 정상 admission이 아닌 invariant violation으로
   `LeaseObservationCode.BACKEND_ERROR`만 내부적으로 기록하고 기존
   `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 fail-closed한다. 이 fatal marker는
   public error, metric tag, listener/event payload로 승격하지 않는다.

   shutdown state는 `LeaderRouteLeaseShutdownCoordinator` 한 곳만 소유하며
   `RUNNING → QUIESCING → DRAINING → DRAINED → CLOSED` 또는
   `DRAINING → CLOSED_WITH_LEAKS`의 단방향 graph만 허용한다. `RUNNING`에서
   `DRAINING/CLOSED`, `QUIESCING`에서 `RUNNING`, `DRAINED/CLOSED(_WITH_LEAKS)`에서
   재활성화하는 전이는 금지하고, 각 상태별 신규 acquire/cleanup/watchdog 허용
   정책과 terminal observation을 state-graph test로 고정한다.

TDD 증거:

- RED: `LeaderRouteLeasePropertiesTest`, `LeaderRouteGuardPropertiesSerializationTest`,
  `LeaderRouteLeaseAutoConfigurationTest`, `LeaseCapabilityResolverTest`,
  `LeaderRouteLeaseShutdownCoordinatorTest`,
  `LeaderRouteLeaseShutdownCoordinatorStateGraphTest`,
  `LeaderElectionStatusEndpointTest`, `LeaderRouteLeaseDiagnosticsTest`,
  `LeaderRouteLeaseObservationRedactionTest`,
  `LeaderElectionObservabilityAutoConfigurationTest`,
  `LeaderBackendDiagnosticsActuatorAutoConfigurationTest`.
- GREEN: binding/default/range/rollback/startup ordering matrix, diagnostics
  redaction across response/log/metric/event/Actuator surfaces, core-only aggregate
  reset/retention, Micrometer-absent fallback, legacy status payload preservation,
  context close/drain state tests.
- `./gradlew --no-daemon :bluetape4k-leader-spring-boot:test --tests '*Lease*Test'`

### 6. Spring MVC request lifecycle

대상 파일:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/mvc/LeaderMvcRouteGuardFactory.kt`
- 신규 `spring/route/mvc/LeaderMvcLeaseRouteGuard.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/mvc/`

작업:

1. 기존 `interceptor(slot)`와 STATE/CUSTOM 동작은 그대로 두고 LEASE factory 경로를
   분리한다. caller slot의 lock name/leader id를 acquirer에 그대로 전달하고 slot 없는
   auto-config만 baseline node id를 사용한다.
2. `preHandle`에서 MVC blocking permit을 먼저 reserve하고
   `maxBlockingWaitTime`까지 동기 acquire한다. contention, admission rejection,
   unsupported capability는 configured rejection status와 빈 body로 끝내며 handler를
   호출하지 않는다. `mvcWaiterPermit`은 waiter마다 별도로 즉시 reserve하고,
   shared `acquireAttemptPermit`/queue slot과 분리한다. shared attempt에 join하는
   duplicate는 두 번째 waiter permit만 가지며 shared permit/queue slot을 새로 만들지
   않는다. reserve 실패 시 이미 확보한 자원을 backend 호출 전에 반환하고,
   terminal callback까지 유지한 뒤 permit을 정확히 한 번 반환한다.
3. success에는 request attribute에 private holder marker와 lease handle을 한 번만
   저장한다. `afterCompletion`과 `AsyncListener`가 동일 terminal callback을 공유한다.
   `onStartAsync` 재등록, registration failure rollback, timeout/error non-terminal
   signal, cross-thread completion, redispatch stale marker를 검증한다.
4. handler exception/ordinary cleanup failure는 primary response/error를 덮지 않는다.
   cancellation/interruption/fatal Error는 규칙대로 보존하고 cleanup observation은
   sanitized sink로 한 번만 남긴다.
5. cleanup은 `LeaseCleanupBoundary.releaseWithin`을 사용하고 deadline 초과 시
   `RESIDUAL_TRANSFERRED`를 terminal completion으로 취급한다. emergency cleanup
   lane이 normal queue에 잠식되지 않게 한다.

   MVC lifecycle test는 새 `ACQUIRING`의 attempt/queue/composite reserve,
   `ACQUIRING → PUBLISHED` 성공 transfer와 callback terminal의 두 permit 정확히 한 번
   반환, cancellation/deadline의 네 resource 보유, `RELEASED`/`NOT_HELD`의 composite
   반환, `RESIDUAL_TRANSFERRED`의 `CLOSED_WITH_LEAKS`/residual counter 관찰, late
   callback discard와 forged marker/slot fingerprint fail-closed를 route 경계에서
   검증한다.

TDD 증거:

- RED: `LeaderMvcLeaseRouteGuardTest`, `LeaderMvcLeaseAsyncCompletionTest`,
  `LeaderMvcLeaseCrossThreadReleaseTest`.
- GREEN: success/contended/exception/async timeout/error/cancel/duplicate interceptor/
  wrong slot or forged attribute/registration rollback and no-leak tests, including
  `mvcWaiterPermit` versus shared permit isolation/refund/hold-until-terminal and
  canonical tuple terminal handoff.
- `./gradlew --no-daemon :bluetape4k-leader-spring-boot:test --tests '*Mvc*Lease*Test'`

### 7. Spring WebFlux sync/suspend lifecycle

대상 파일:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/webflux/LeaderWebFluxRouteGuardFactory.kt`
- 신규 `spring/route/webflux/LeaderWebFluxLeaseRouteGuard.kt`
- 신규 `spring/route/webflux/SuspendLeaderLeaseLifecycle.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/webflux/`

작업:

1. 기존 passive filter를 보존하고 LEASE filter는 Spring이 소유하는 bounded
   dedicated `acquireScheduler`/`cleanupScheduler`를 사용한다. event-loop에서
   blocking acquire/cleanup을 하지 않으며 scheduler는 context shutdown에서 drain 후
   dispose한다. 기존 factory의 caller-provided `evaluationScheduler`는 acquire 경계에
   대한 compatibility bridge로만 검증·매핑하고 cleanup 경계에 사용하거나 factory가
   dispose하지 않는다. injected scheduler가 blocking-capacity/queue contract를
   증명하지 못하면 `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 startup 또는 request를
   fail-closed한다.
2. `filter(slot)` 호출이 제공한 `LeaderSlot.lockName`과 `leaderId`를 acquirer에
   그대로 전달하고, slot 없는 auto-config만 캡처한 baseline node id를 사용한다.
   request attribute의 private holder marker에는 slot fingerprint를 함께 묶고,
   다른 slot/redispatch에서 온 forged marker는 backend 호출 전에 fail-closed한다.
3. sync/non-suspend chain은 `Mono.usingWhen` resource lifecycle로 complete/error/cancel을
   모두 cleanup한다. queued cancellation은 acquire/handler를 시작하지 않고,
   post-acquire cancellation은 handle을 한 번만 닫는다. late-acquire discard는
   holder가 공개되기 전 conditional rollback을 수행한다.
4. suspend handler 경로는 `SuspendLeaderLeaseAcquirer`와 suspend handle만 사용한다.
   `NonCancellable` cleanup을 bounded deadline 안에서 수행하고 `runBlocking`을
   도입하지 않는다. capability가 없으면 stable suspend-unsupported code로 fail-fast한다.
5. ordinary cleanup error는 primary signal/value를 덮지 않고 sanitized suppressed
   observation만 남긴다. primary가 없을 때만 fatal Error/interruption을 전파한다.
6. subscriber별 waiter/use reference를 관리해 joiner cancellation이 다른 subscriber의
   LIVE lease를 닫지 않게 한다. queue flood/rejection과 fixed metrics를 검증한다.

   WebFlux lifecycle test는 MVC와 같은 canonical tuple/terminal matrix를
   `Mono.usingWhen` complete/error/cancel, queued cancellation, post-acquire cancellation,
   late-acquire rollback에서 검증한다. `RELEASED`/`NOT_HELD`는 composite를 pool로
   반환하고, deadline/error/timeout은 `RESIDUAL_TRANSFERRED`와
   `CLOSED_WITH_LEAKS`로 종료되는지 확인한다. Spring-owned dedicated scheduler가
   context close 뒤 drain 후 dispose되고 caller-provided compatibility scheduler는
   dispose되지 않는지도 각각 확인한다.

TDD 증거:

- RED: `LeaderWebFluxLeaseRouteGuardTest`, `LeaderWebFluxLeaseCancellationTest`,
  `LeaderWebFluxLeaseSlotIdentityTest`, `LeaderWebFluxSuspendLeaseRouteGuardTest`.
- GREEN: usingWhen complete/error/cancel, queued/post-acquire cancellation, late acquire,
  primary-signal preservation, event-loop marker, caller slot lock-name/leader-id
  preservation, slot fingerprint/forged marker rejection, scheduler ownership, duplicate
  filter, bounded queue and cross-subscriber reference tests.
- `./gradlew --no-daemon :bluetape4k-leader-spring-boot:test --tests '*WebFlux*Lease*Test'`

### 8. wrappers, metrics, documentation, metadata

대상 파일:

- `leader-micrometer/src/main/kotlin/**/InstrumentedLeaderElectors.kt` 및 테스트
- listener/tenant wrapper 파일과 tests
- `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`
- `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- 신규 또는 수정된 public Kotlin API KDoc
- `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/Issue607LeaseLifecycleBenchmark.kt`
- `benchmark/build.gradle.kts` (Issue #607 JMH target/report configuration only)
- `scripts/benchmarks/compare_issue607_lease.py`
- `scripts/benchmarks/compare_issue607_lease_test.py`
- `docs/benchmarks/issue607-baseline-56a33db4.json` (same-host baseline evidence)
- 현재 pinned manual 파일은 비변경 검증 대상

작업:

1. wrapper가 capability를 정적으로 보존할 때만 adapter view를 제공하고, lifecycle/
   watchdog/release/event를 중복 생성하지 않도록 한다. capability 없는 delegate는
   기존 view만 노출한다.
2. backend-call metric의 `phase={acquire,release,extend,watchdog}`와
   `outcome={success,not-held,error,timeout}`를 고정한다. route metrics는 core aggregate
   와 MVC/WebFlux local view를 혼합하지 않고 fixed tag allow-list를 사용한다. exact
   meter/Actuator schema, `runtimeState` allow-list, core-only capacity, reset/retention,
   Micrometer-absent fallback을 `LeaderElectionStatusEndpointTest`,
   `LeaderRouteLeaseDiagnosticsContributorTest`, `LeaderRouteLeaseMetricsSchemaTest`,
   `LeaderRouteLeaseRunbookContractTest`로 고정한다. 각 meter의 exact name/type/unit/
   framework/phase/outcome/reason allow-list와 increase denominator query를 fixture로
   비교한다. 예외적으로 `drain.residual.expired`는 `reason={unknown,quarantined}`만,
   `drain.retries`는 `outcome={scheduled,exhausted}`만 허용하고 그 외 dynamic
   reason/tag는 schema test에서 거부한다. runbook은 15초 scrape, 5% rejection ratio, queue/core capacity 80%,
   residual 0 초과 2 scrape alert, positive queue capacity와 force-unlock 금지 절차를
   동일 query로 검증한다.
3. metadata에 13개 lease key의 type/default/range/description을 추가한다. README EN/KO는
   opt-in YAML, `max-active-leases: 10000`, `max-residual-leases: 1024`,
   `effectiveActiveCapacity = min(10000, 1024) = 1024` 파생값과 bind 불가 규칙,
   capability selection, rejection/cleanup/cancellation/max lifetime, rollback을
   동일한 구조로 설명한다. `STATE`/`CUSTOM` passive semantics와 `LEASE`를 명확히
   분리한다. runbook은 15초 scrape, 5% rejection ratio, queue/core capacity 80%,
   residual 0 초과 2 scrape, force-unlock 금지와 대응 절차를 고정한다.
   `ExtendOutcome.Rejected`를 추가한 모든 exhaustive `when` caller를 함께
   마이그레이션하고, EN/KO 문서에 기존 `evaluationScheduler`는 acquire compatibility
   bridge로만 검증·매핑되며 cleanup에는 사용하지 않고 factory가 dispose하지 않는다는
   소유권을 명시한다.
4. `docs/manual/manifest.yaml` pin을 변경하지 않고 release inventory/manual contract를
   실행한다. 1.0.0 pin 전에는 새 public API claim을 manual에 추가하지 않는다.

5. response, log, metric, event/listener, Actuator의 다섯 surface 각각에서 raw token,
   lock name, leader identity, backend URI, exception class/name/message가 0건임을
   contract test로 확인한다. `LEASE` route는 raw `LeaderElectionListener`/log listener를
   등록하지 않고 `SanitizedRouteLeaseObservationSink`만 최대 한 번 호출한다. 고정
   `LeaseObservationCode`와 `LEADER_ROUTE_*` error mapping 외의 dynamic reason/tag는
   테스트에서 거부한다.

TDD/문서 증거:

- `InstrumentedLeaderElectorsLeaseTest`, `ListeningLeaderElectorsLeaseTest`,
  `TenantScopedLeaderElectorsLeaseTest`로 event/metric/capability parity를 검증한다.
- `LeaderConfigurationMetadataTest`, README parity test, public API KDoc/API dump를
  실행하고 `rg "when.*ExtendOutcome|when \(.*ExtendOutcome"` caller inventory를
  compile/test로 소진해 `Rejected` exhaustive branch를 누락하지 않는다.
- 한국어 terminology audit는
  `/Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs`를
  실제 skill 경로로 실행하고 findings를 기록한다.

### 9. 통합 검증, 성능, CI와 delivery

검증 순서:

1. `git diff --check`와 untracked artifact check를 실행한다.
2. `./gradlew --no-daemon :bluetape4k-leader-core:test` 및 변경된 backend targeted
   tests를 실행한다. Testcontainers는 `colima status`, `docker context show`,
   `docker info`를 먼저 확인하고 sequential로 수행하며 skip/실패는 성공으로 세지 않는다.
3. `./gradlew --no-daemon :bluetape4k-leader-spring-boot:test`
4. `./gradlew --no-daemon :bluetape4k-leader-spring-boot:build`
5. `./gradlew --no-daemon detekt`
6. `./gradlew --no-daemon checkBinaryCompatibility`와
   `python3 scripts/compatibility/check_binary_api_test.py`를 실행하여 기존
   descriptors와 1.0.0 additive API를 확인한다. Gradle task 이름은 root
   `build.gradle.kts`의 `checkBinaryCompatibility` 정의와 일치시킨다.
7. manual contract 순서:

   ```bash
   python3 scripts/ci/validate_ci_fanout.py --static
   ./gradlew exportManualModuleInventory --no-daemon --no-configuration-cache
   MANUAL_REF="$(ruby -e 'require "yaml"; puts YAML.load_file("docs/manual/manifest.yaml")["releaseRef"]')"
   MANUAL_SHA="$(ruby -e 'require "yaml"; puts YAML.load_file("docs/manual/manifest.yaml")["releaseCommit"]')"
   ruby scripts/manual/release_inventory.rb "$MANUAL_REF" "$MANUAL_SHA" \
     build/manual/module-inventory.json build/manual/release-module-inventory.json 35
   ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json docs/manual/manifest.yaml
   ruby scripts/manual/validate_release_manuals.rb "$MANUAL_REF" "$MANUAL_SHA"
   ruby scripts/manual/export_manifest.rb --check
   ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
   python3 scripts/ci/validate_manual_contract.py
   ```

8. `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/Issue607LeaseLifecycleBenchmark.kt`
   를 추가하고 `@Warmup(iterations = 3)`, `@Measurement(iterations = 10)`,
   `@Fork(1)`, `@Threads(1)`을 클래스에 고정한다. benchmark rows는 explicit lease와
   `runIfLeader`, local 및 대표 remote backend인 Redis/Lettuce Testcontainer,
   MVC/WebFlux adapter, autoExtend N=1000, event-loop/cleanup queue flood를 포함한다.
   `@BenchmarkMode(Mode.Throughput, Mode.SampleTime, Mode.AverageTime)`으로
   throughput와 sample-time p95/p99를 모두 산출하고 resource/registry size,
   `sharedSchedulerInstances`, `logicalSerialLanes`, `scheduledTimerTasks`,
   `extensionQueue`를 반환한다. core/local row는
   `sharedSchedulerInstances=1`을, Spring row는
   `springAcquireSchedulers=1`, `springCleanupSchedulers=1`,
   `callerCompatibilitySchedulerDisposed=false`를 각각 검증하며 이 값을 전역
   단일 카운터로 합산하지 않는다.
   `benchmark/build.gradle.kts`에는 기존 main/averageTime을 바꾸지 않는 `issue607`
   configuration과 `sample` report를 추가하며 생성 task는
   `:benchmark:benchmarkIssue607Benchmark`로 고정한다. 실행 명령은
   `./gradlew --no-daemon :benchmark:benchmarkIssue607Benchmark --no-configuration-cache --rerun-tasks`
   이고 결과 JSON은 `benchmark/build/reports/benchmarks/issue607/candidate.json`으로
   복사한다.
9. `Issue607LeaseLifecycleBenchmark`는 새 public lease type을 정적으로 참조하지
   않고 reflection adapter로 optional `LeaderLeaseAcquirer`를 찾는다. 따라서
   같은 source와 `issue607` Gradle configuration patch를 detached baseline
   worktree에 임시 적용해도 base API로 compile된다. baseline 절차는
   `git worktree add --detach build/issue607-baseline
   56a33db44e22fb137e205119dd853f153cff3402` 후 candidate의
   `Issue607LeaseLifecycleBenchmark.kt`와 `benchmark/build.gradle.kts`의 issue607
   configuration만 patch로 적용하고, `-Pissue607Rows=legacy`를 실행한다. base에는
   lease reflection capability가 없으므로 explicit rows는 실행하지 않고
   `legacyRunIfLeader` rows만 JSON에 남긴다. candidate는 같은 harness에서
   `-Pissue607Rows=all`을 실행해 `legacyRunIfLeader`와 `explicitLease`를 함께
   남긴다. 두 결과는 동일 host/JDK/Docker/Redis image와 warmup/measurement를
   기록하고, baseline worktree는 결과 확인 뒤 no-force로 제거한다.
10. `scripts/benchmarks/compare_issue607_lease.py --baseline
   docs/benchmarks/issue607-baseline-56a33db4.json --candidate
   benchmark/build/reports/benchmarks/issue607/candidate.json --max-regression 0.10`
   로 row mapping을 고정해 `baseline.legacyRunIfLeader ↔
   candidate.legacyRunIfLeader`만 10% regression gate에 사용한다. candidate 내부의
   `explicitLease ↔ legacyRunIfLeader`는 상대 overhead 보고일 뿐 baseline gate와
   섞지 않으며, Redis/Lettuce, MVC/WebFlux, autoExtend, flood도 같은 scenario key
   끼리만 비교한다. p95/p99/throughput는 SampleTime/Throughput/AverageTime
   결과를 각각 읽는다. `scripts/benchmarks/compare_issue607_lease_test.py`
   는 JMH JSON schema, baseline identity, sample-time percentile, 10% gate, logical
   call budget을 검증한다. successful lifecycle의 acquire/release는 각 1회,
   duplicate instrumentation 추가 call은 0회, watchdog tick은
   `ceil(activeDuration/renewalPeriod) ± 1`회, core/local row의
   `sharedSchedulerInstances=1`, Spring row의
   `springAcquireSchedulers=1` 및 `springCleanupSchedulers=1`,
   `logicalSerialLanes<=activeHandles`, `scheduledTimerTasks<=activeHandles`,
   `extensionQueue<=maxWatchdogInFlight`, `watchdogInFlight<=maxWatchdogInFlight`여야
   한다.
11. CI는 `.github/workflows/ci.yml`의 `ci-contract`, `manual-contract`, `build`,
   `test-core`, `test-spring-boot`, 변경 backend `test-*`, `ci-status`를 확인한다.
   nightly 대상 backend는 `.github/workflows/nightly-tests.yml`의 대응 job과
   `nightly-status`를 별도 확인하며 path-filter skipped를 coverage PASS로 세지 않는다.
12. 최종 독립 review에서 P0/P1이 0인지 확인하고, lesson artifact를 작성한 뒤
    approved branch에서 Korean Lore commit으로 커밋한다. PR 생성 전 exact head,
    base, checks, labels, milestone, linked issue, `## DoD Status`를 fresh-read한다.

## 파일별 acceptance mapping

| acceptance 영역 | 구현 파일/영역 | 검증 |
|---|---|---|
| additive API와 JVM surface | core API/contract files | API/serialization/ABI tests |
| handle ownership·fencing·cleanup | lifecycle/admission/residual internals | race, stale handle, deadline tests |
| action/wrapper parity | local/backend electors, listener/micrometer/tenant wrappers | common contract + duplicate event/metric tests |
| backend matrix | Redis, SQL, document/cloud, coordination, platform/cache | backend contract/integration tests |
| route mode/selector/properties | Spring properties, auto-config, capability resolver | startup/binding/rollback/AOT matrix |
| MVC request boundary | MVC factory/lease guard | preHandle/afterCompletion/async/cross-thread tests |
| WebFlux request boundary | WebFlux factory/lease guard/suspend lifecycle | usingWhen/cancel/scheduler/primary-signal tests |
| admission/capacity/observability | route properties, diagnostics, metrics, shutdown | flood, capacity, redaction, drain tests |
| performance/overhead gate | Issue607 JMH benchmark, compare scripts, baseline artifact | sample p95/p99, throughput, resource/task/call budgets |
| docs/manual/compatibility | README, metadata, KDoc, manual validators | parity, terminology, manual, binary checks |

## 리스크와 롤백

- backend가 completion thread release 또는 deadline-aware transport를 지원하지
  않으면 억지 bridge를 만들지 않고 capability를 노출하지 않는다. built-in backend가
  모두 matrix를 통과하지 못하면 해당 backend migration commit을 revert하고 issue를
  blocker로 남긴다.
- public property/API descriptor drift가 발견되면 implementation을 중지하고 기존
  constructor/copy/serialization bridge를 먼저 복구한다.
- queue/residual bound를 결정적으로 증명하지 못하면 LEASE default를 활성화하지 않고
  `STATE` fallback을 유지한다.
- route cleanup이 primary signal을 대체하면 해당 adapter commit을 revert하고
  `Mono.usingWhen`/`afterCompletion` boundary 테스트를 먼저 고친다.
- release rollout은 `enabled=false` 유지 → capability preflight → canary → bounded
  metric/health 확인 → 단계 확대 순서다. cleanup/drain/residual threshold 초과 시
  `authority-mode=STATE` 또는 `enabled=false`로 rollback한다.

## Plan Writer DoD (SPW-01..SPW-05)

| gate | status | evidence |
|---|---|---|
| SPW-01 scope, audience, source ledger, identifiers, unknowns | PASS | live #607/#700/#537, approved option 2 spec, current core/route anchors, base SHA와 제외 범위를 기록했다. |
| SPW-02 executable implementation plan | PASS | Tasks 1-12에 정확한 파일, 의존 순서, RED→GREEN 테스트, 명령, CI/manual/benchmark/rollback stop condition을 고정했다. |
| SPW-03 Korean reader-facing plan quality | PASS | `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/superpowers/plans/2026-08-24-issue-607-request-lease-plan.md` 결과 `findings=0`. |
| SPW-04 spec-to-plan traceability | PASS | acceptance 영역별 파일·테스트 mapping과 backend/Spring lifecycle matrix를 기록했다. |
| SPW-05 final Markdown readback | PASS | 전체 read-back, TODO/TBD/placeholder/FIXME 점검, `git diff --check` 통과; 계획의 의도적 `PENDING`은 review/commit 전 상태 항목뿐이다. |

## Plan DoD

- [ ] 계획 파일이 writer audit/readback을 통과한다.
- [ ] 여섯 독립 plan review가 모두 `P0=0`, `P1=0`으로 통합된다.
- [ ] 이 계획과 승인된 spec이 Lore protocol commit으로 기록된다.
- [ ] 구현 전 stop condition: core API/admission/lifecycle contract가 green이 아니면
  backend 또는 Spring implementation으로 진행하지 않는다.
- [ ] 구현 후 stop condition: targeted test, detekt, binary compatibility, manual
  contract, CI exact-head evidence가 없으면 PR/merge를 진행하지 않는다.
