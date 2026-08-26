# Issue #532 운영 lock management action 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 승인된 Issue #532 명세를 `leader-core`, `leader-micrometer`,
`leader-spring-boot`, `leader-ktor`에 additive capability로 구현한다. 운영자가
등록된 single-leader lease만 안전하게 해제할 수 있도록 소유권 pre-check, bounded
admission, release/post-check, timeout/quarantine, shutdown drain, sanitized
observability를 하나의 계약으로 고정하고, Spring Actuator와 Ktor의 write surface는
각각 명시적인 opt-in과 애플리케이션 소유 인증 경계 뒤에 둔다.

**구조:** core가 blocking/suspend registry, 공통 lock-name/HTTP outcome 계약,
phase와 quarantine 수명주기를 소유한다. Micrometer는 core의 sanitized terminal
observation을 고정된 reason/phase/surface 태그로 계측한다. Spring은 기존
read-only `leaderElection` endpoint/JMX를 유지하면서 별도의 `@WebEndpoint`
write endpoint와 registry lifecycle을 추가한다. Ktor는 기존 GET route와 분리된
명시적 `Route` extension만 제공하고, `authenticate("management")` 및
애플리케이션 `authorize` callback은 호출자가 소유한다. 기존 `runIfLeader`,
`LeaderRouteLeaseRuntime`, `leaderScheduled`에는 자동 등록하지 않는다.

**기술 스택:** Kotlin/JVM, Kotlin coroutines, Spring Boot Actuator 4.x,
Ktor 3.x, Micrometer, JUnit 5, Kluent/Bluetape assertions,
`io.bluetape4k.support` validation helpers, `LeaseOperationScheduler`,
`MonotonicDeadline`, `SupervisorJob`, Gradle multi-module build.

## 결과 목표와 종료 조건

다음 항목이 모두 충족될 때 계획을 완료로 판정한다.

1. 승인 명세의 public API, outcome truth table, timeout/cancellation/quarantine,
   lifecycle, auth, HTTP mapping, retry, metrics, 문서 경계가 코드와 테스트에
   일대일로 추적된다.
2. blocking과 suspend registry가 동일한 결과 불변식을 지키며, backend callback은
   lock당 최대 한 번이고 callback 동안 registry mutex를 점유하지 않는다.
3. scheduler/worker/registration cap은 bounded 상태이고 queue overflow는
   호출자 스레드에서 실행되지 않으며, timeout 뒤 실제 worker 종료 전에는 capacity가
   회복되지 않는다.
4. Spring action은 부모 endpoint와 nested `actions.enabled`가 모두 true일 때만
   생성되고, 기존 status/JMX endpoint와 ID가 겹치지 않는다. Ktor action은
   application route와 인증 scope 밖에서 설치되거나 자동 노출되지 않는다.
5. observer와 Micrometer event에는 lock name, actor, credential, token,
   backend payload, exception text가 없고, quarantine counter/gauge는 worker의
   실제 종료와 함께 회복된다.
6. 기존 public JVM descriptor, serialization, GET/JMX/status JSON, plugin default,
   `runIfLeader` semantics가 회귀하지 않는다.
7. targeted test, 네 모듈 test/check, detekt, ABI, manual contract, terminology
   audit, CI exact-head evidence와 독립 read-only review가 통과한다.

구현하지 않는 항목은 group/semaphore lease action, 전략적 election action,
`LeaderRouteLeaseRuntime.AdmissionBoundLeaseHandle` 또는 `leaderScheduled` 자동
등록, JMX write operation, durable actor audit export(#535), force unlock,
backend lock rename/conversion, 자동 retry, 새로운 backend/dependency/module이다.

## 구현 전 계약 고정

아래 결정은 승인 명세의 안전 경계를 보존하면서 구현 불가능한 서술 충돌을 해소한다.
계획 승인 후 이 계약을 먼저 테스트로 고정하고, 그 전에는 production implementation을
작성하지 않는다.

### 1. Observer event와 quarantine reason

- `LeaderManagementActionObservation`에는 `quarantineReason: LeaderManagementQuarantineReason?`
  를 additive nullable property로 둔다. reason enum은
  `CLEANUP_TIMEOUT`, `NON_INTERRUPTIBLE`, `CALLBACK_ERROR`, `CLOSE_TIMEOUT` 네 값만
  갖고 Micrometer 문자열은 각각 `cleanup-timeout`, `non-interruptible`,
  `callback-error`, `close-timeout`으로 고정한다.
- observer는 중간 phase마다 event를 발행하지 않는다. admission된 `release`마다
  terminal event를 정확히 한 번 발행한다. 정상/비격리 결과는
  `phase=TERMINALIZED`, 격리된 결과는 `phase=QUARANTINED`이며 outcome과
  `mutationAttempted`는 최종 결과 불변식을 따른다. `ADMITTED`, `PRECHECK`,
  `RELEASE_STARTED`, `POSTCHECK`는 내부 CAS 상태로만 남긴다.
- `Error`는 caller에게 재전파하되, finally에서 sanitized terminal observation을
  발행한다. pre-check `Error`는 `OWNERSHIP_UNKNOWN`, release `Error`는
  `RELEASE_FAILED`/`CALLBACK_ERROR`, post-check `Error`는
  `RELEASE_UNCONFIRMED`/`CALLBACK_ERROR`로 기록한다. release 이후 `Error`는
  reservation을 quarantine한다.
- close timeout은 이미 terminal event가 발행된 action을 중복 발행하지 않는다.
  종료 시점까지 terminalize되지 않은 reservation은 최종 event의 reason을
  `CLOSE_TIMEOUT`으로 고정하고, registry lifecycle metric은 별도 내부 경로에서
  한 번만 증가시킨다. observer callback은 mutex 밖에서 실행하고 예외를 삼킨다.

### 2. Surface ownership

- core registry가 직접 받은 observer event의 surface는 `CORE`다.
- Spring default registry는 surface-decorating observer를 통해 `SPRING`으로
  기록하고, Ktor 애플리케이션은 registry 생성 시 같은 decorator를 선택해
  `KTOR`로 기록한다.
- application-owned custom registry의 observer와 lifecycle은 강제로 교체하지
  않는다. custom observer가 surface를 정하도록 문서화하고 adapter는 response와
  auth 경계만 책임진다. 이 규칙으로 library가 caller-owned registry/scope를
  닫거나 observer를 몰래 대체하지 않는다.

### 3. Ktor shutdown receiver

`Application`에는 `engine` property가 없으므로 명세의 예시를 다음 additive
extension으로 구현한다.

```kotlin
suspend fun ApplicationEngine.stopLeaderManagementGracefully(
    registry: SuspendLeaderManagementActionRegistry,
    gracePeriodMillis: Long = 1_000,
    timeoutMillis: Long = 5_000,
)
```

호출자는 `engine.stopLeaderManagementGracefully(registry, ...)`를 engine stop
직전에 호출한다. library는 `ApplicationStopping`, `runBlocking`, 외부
application scope 취소를 사용하지 않는다.

### 4. Release-pinned manual provenance

현재 `docs/manual/manifest.yaml`은 `releaseRef: 0.5.0`,
`releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977`에 고정되어 있다.
새 #532 API가 그 commit에 존재하지 않으므로 `docs/manual/en/**`와
`docs/manual/ko/**`의 pinned source-of-truth 파일은 이번 train에서 수정하지
않는다. 대신 다음 six draft 파일에 EN/KO 운영 문서를 작성하고 각 draft의 첫
부분에 unreleased 상태와 다음 manifest 승격 조건을 명시한다.

- `docs/manual/drafts/2026-08-26-issue-532-management-action-spring.en.md`
- `docs/manual/drafts/2026-08-26-issue-532-management-action-spring.ko.md`
- `docs/manual/drafts/2026-08-26-issue-532-management-action-ktor.en.md`
- `docs/manual/drafts/2026-08-26-issue-532-management-action-ktor.ko.md`
- `docs/manual/drafts/2026-08-26-issue-532-management-action-operations.en.md`
- `docs/manual/drafts/2026-08-26-issue-532-management-action-operations.ko.md`

release pin 승격은 별도 publish/release gate이며 #532 merge의 전제 조건이 아니다.
기존 manual validation은 immutable `0.5.0` inventory에 대해 계속 PASS해야 한다.

### 5. Spring property와 lifecycle 범위

`LeaderManagementActionProperties`는 별도 `@ConfigurationProperties`로 만들고
기존 public `LeaderProperties` data class를 수정하지 않는다. canonical binding
prefix는 Spring relaxed binding이 처리하는
`management.endpoint.leader-election.actions`를 사용하며 metadata와 테스트에는
호환 표기인 `management.endpoint.leaderElection.actions.*`도 함께 고정한다.
노출되는 property는 다음 두 개다.

```text
management.endpoint.leaderElection.actions.enabled=false
management.endpoint.leaderElection.actions.timeout=5s
```

registry의 cleanup grace, concurrency, queue, registration, close caps는 core의
검증된 기본값을 사용한다. timeout은 core hard maximum 30초 안에서만 허용한다.
`actions.enabled=true`인 경우에만 library-owned default registry와 그 lifecycle
drain bean을 만들며, custom registry bean이 있으면 이를 대체하거나 닫지 않는다.

## 파일·API 영향 표

| 영역 | 생성 파일 | 수정 파일 | 핵심 책임 |
| --- | --- | --- | --- |
| Core contract | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderManagementAction.kt`, `LeaderManagementLockName.kt`, `LeaderManagementHttpContract.kt` | 없음 | enum/result/registration/observer, ASCII selector, 공통 status/retry mapping |
| Core blocking | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderManagementActionRegistry.kt`, `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaderManagementActionStore.kt` | `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaseOperationScheduler.kt` | identity/ref-count, bounded admission, phase CAS, timeout/quarantine, drain |
| Core suspend | `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderManagementActionRegistry.kt` | 없음 | SupervisorJob worker, caller cancellation 분리, 동일 truth table |
| Micrometer | `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderManagementActionObserver.kt` | `MicrometerNames.kt` | low-cardinality counter/gauge와 recovery |
| Spring | `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderManagementActionProperties.kt`, `.../observability/LeaderElectionActionWebEndpoint.kt`, `.../observability/LeaderElectionManagementActionAutoConfiguration.kt`, `.../observability/LeaderManagementActionLifecycle.kt` | `LeaderElectionActuatorAutoConfiguration.kt`는 status ABI만 유지, `META-INF/spring/additional-spring-configuration-metadata.json`, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | nested opt-in, default/custom registry, `@WebEndpoint` write response, drain 순서 |
| Ktor | `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementActionRoute.kt`, `LeaderManagementShutdown.kt` | `LeaderElectionPluginConfig.kt`의 additive 설정만 수정 | explicit POST route, auth/authorize boundary, manual JSON, engine shutdown helper |
| Core tests | `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderManagementActionModelTest.kt`, `LeaderManagementLockNameTest.kt`, `LeaderManagementActionRegistryTest.kt`, `.../coroutines/SuspendLeaderManagementActionRegistryTest.kt` | scheduler 회귀 test 추가 | contract/TDD, race, timeout, cancellation, no-leak |
| Micrometer tests | `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderManagementActionObserverTest.kt` | 없음 | fixed meter names/tags/gauge recovery |
| Spring tests | `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionManagementActionAutoConfigurationTest.kt`, `LeaderElectionActionWebEndpointTest.kt`, `LeaderElectionManagementActionHttpTest.kt` | metadata/status/JMX regression test | bean conditions, HTTP matrix, ABI/non-exposure |
| Ktor tests | `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementActionRouteTest.kt`, `LeaderManagementShutdownTest.kt` | existing GET/plugin tests | auth/path/status/cancellation/lifecycle |
| Docs | module `README.md`/`README.ko.md` for core, Spring, Ktor; six `docs/manual/drafts/...` | top-level README links only when needed | opt-in, ownership, retry, quarantine, rollback, unreleased provenance |

파일명은 구현 중 임의로 바꾸지 않는다. 새 파일이 필요하면 먼저 이 표와 해당
작업의 추적성 목록을 함께 수정하고 `git diff --check`를 다시 실행한다.

## 작업 순서와 TDD 증거

### 작업 0. 계획·명세 reconciliation 및 RED harness

**대상:** 이 계획 파일, 승인 명세
`docs/superpowers/specs/2026-08-26-issue-532-management-action-design.md`,
리뷰 `docs/superpowers/reviews/2026-08-26-issue-532-management-action-spec-review.md`.

- [ ] 승인된 명세의 각 acceptance 항목을 `Core`, `Micrometer`, `Spring`, `Ktor`,
  `Docs` 추적성 표로 복사하고 각 항목에 정확한 test class/method 이름을 배정한다.
- [ ] `LeaderManagementQuarantineReason`, terminal-only observer cardinality,
  `ApplicationEngine` shutdown receiver, release-pinned manual draft 경계를
  이 계획의 계약으로 고정한다. 기존 명세 원문은 승인된 의도를 보존하되,
  불가능한 receiver와 release provenance를 그대로 구현하지 않는다.
- [ ] 테스트 디렉터리에 컴파일 가능한 fake handle/clock/latch fixture의 최소
  skeleton과 contract test 이름을 먼저 추가한다. 이 단계에서는 production
  registry가 없으므로 targeted Gradle test가 RED(미해결 symbol 또는 assertion)
  이어야 한다.
- [ ] `io.bluetape4k.assertions.assertFailsWith`와 기존 Kluent matcher를 사용하고,
  `assertThrows`, `kotlin.test.assertFailsWith`, `invoking { } shouldThrow`를
  새 테스트에 사용하지 않는다.

**검증 명령과 예상 결과:**

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests 'io.bluetape4k.leader.LeaderManagementActionModelTest' \
  --tests 'io.bluetape4k.leader.LeaderManagementLockNameTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과는 새 contract type이 아직 없다는 `compilation failure`이며, 이 RED
출력이 테스트가 계약을 실제로 참조한다는 증거다. 실패 로그는 구현 전에 기록하고,
fixture가 production API를 우회하지 않는지 확인한다.

### 작업 1. Core public contract와 lock-name validation

**생성/수정 파일:**

- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderManagementAction.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderManagementLockName.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderManagementHttpContract.kt`
- `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderManagementActionModelTest.kt`
- `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderManagementLockNameTest.kt`

**구현 계약:**

- `LeaderManagementAction.RELEASE`와 명세의 13개 outcome을 순서 변경 없이
  추가한다. 결과는 `data class LeaderManagementActionResult(...): Serializable`로
  만들고 `serialVersionUID=1L`을 유지한다. `copy`, component, Java getter,
  `toString`에 backend/token/exception을 넣지 않는다.
- `LeaderManagementRegistrationOutcome` 네 값과
  `LeaderManagementRegistration(accepted, outcome)`를 추가한다. 생성자는
  `internal` callback 인자로 보호하고 public property는 두 값만 노출한다.
  accepted token과 cap/closed no-op token 모두 `close()`가 idempotent이며 backend
  I/O나 대기를 하지 않는다.
- `LeaderManagementActionSurface`, `LeaderManagementActionPhase`,
  `LeaderManagementQuarantineReason`, `LeaderManagementActionObservation`,
  `fun interface LeaderManagementActionObserver`를 추가한다. observation에는
  `surface`, `outcome`, `phase`, `mutationAttempted`, `quarantined`, nullable
  `quarantineReason`만 둔다. 불변식 위반 조합은 internal factory에서 만들 수 없게
  한다.
- lock helper는 `isManagementActionLockName(String): Boolean`와
  `requireManagementActionLockName(String)`이다. ASCII
  `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`만 허용하고 `.`, `..`, `%`, `*`, `/`, `\\`,
  control, non-ASCII, 128 byte 초과를 거부한다. boolean helper는 예외를 던지지
  않고, require helper는 `requireNotBlank`, `requireLe` 등 Bluetape helper를
  조합한다. 기존 `validateLockName`의 255자 backend 공통 규칙은 수정하지 않는다.
- HTTP contract는 프레임워크 타입 없이 `LeaderManagementActionResult`에 대한
  integer status와 `retryAllowed=false` 기본 정책을 제공한다. `RELEASED=200`,
  `INVALID_LOCK_NAME=400`, `NOT_REGISTERED=404`, `AMBIGUOUS/NOT_HELD/ACTION_IN_PROGRESS=409`,
  `ACTION_ADMISSION_REJECTED=429`, ownership/release/closed 계열=503,
  `ACTION_TIMED_OUT=504`를 고정한다. `mutationAttempted`는 결과에서 재계산하지
  않고 core phase 값만 전달한다.

**TDD 단계:**

- [ ] RED: 허용/거부 selector, 기존 validator 회귀, enum 순서, result serialization,
  registration token idempotence, observer field allow-list, HTTP matrix test를
  작성한다.
- [ ] GREEN: 위 public contract와 helper를 구현하고 테스트를 통과시킨다.
- [ ] REFACTOR: KDoc은 한국어로 정리하고 raw numeric `require`를 없애며,
  `serialVersionUID` 및 public constructor descriptor를 API test로 고정한다.

**검증:**

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests '*LeaderManagementActionModelTest' \
  --tests '*LeaderManagementLockNameTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과: contract test 전체 `BUILD SUCCESSFUL`; invalid selector는 backend
fixture를 호출하지 않고, 기존 `LockNameValidatorTest`도 PASS한다.

**커밋 경계:** `운영 action의 core 타입과 selector 안전 경계를 고정한다`.
커밋 본문에는 Lore trailer를 사용한다: `Constraint`, `Rejected`, `Confidence`,
`Scope-risk`, `Directive`, `Tested`, `Not-tested`를 모두 실제 검증 내용으로
채우고 구현하지 않은 registry는 `Not-tested`에 명시한다.

### 작업 2. Blocking registry와 bounded scheduler

**생성/수정 파일:**

- `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderManagementActionRegistry.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaderManagementActionStore.kt`
- `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/LeaseOperationScheduler.kt`
- `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderManagementActionRegistryTest.kt`
- `leader-core/src/test/kotlin/io/bluetape4k/leader/internal/LeaseOperationSchedulerTest.kt`

**생성자와 공개 method:**

```kotlin
class LeaderManagementActionRegistry(
    observer: LeaderManagementActionObserver? = null,
    actionTimeout: kotlin.time.Duration = 5.seconds,
    cleanupGrace: kotlin.time.Duration = 30.seconds,
    maxInFlightActions: Int = 16,
    actionQueueCapacity: Int = 32,
    maxRegistrations: Int = 1_024,
    closeTimeout: kotlin.time.Duration = 5.seconds,
) : AutoCloseable {
    fun register(handle: LeaderLeaseHandle): LeaderManagementRegistration
    fun release(lockName: String): LeaderManagementActionResult
    fun registeredLockNames(): List<String>
    fun quarantinedCount(): Int
    fun closeAndDrain(): Boolean
    override fun close()
}
```

모든 Duration은 양의 유한 값이고 action/cleanup/close는 30초 이하, integer cap은
`maxInFlight<=256`, queue `<=1024`, registration `<=65_536`이다. 검증은
`requireGt`, `requireLe`, `requirePositiveNumber`, `requireFinite`를 조합한다.
기존 `LeaseOperationScheduler`의 생성자 descriptor와 `AbortPolicy`를 유지하면서
registry-owned instance의 close/drain timeout을 주입할 수 있는 additive internal
경로만 추가한다. `CallerRunsPolicy`와 caller-owned executor는 사용하지 않는다.

**상태 저장과 선형화:**

- `LeaderManagementActionStore`는 하나의 `ReentrantLock` 아래 lock-name map,
  `IdentityHashMap<LeaderLeaseHandle, RegistrationRecord>`, registration token
  count, per-lock action reservation, lifecycle `OPEN/QUIESCING/CLOSED`,
  quarantine records를 관리한다. callback 호출은 lock을 푼 뒤 실행한다.
- 같은 handle의 반복 register는 identity reference count를 올리고 서로 다른
  handle은 같은 lock-name의 별도 record로 둔다. `maxRegistrations`는 token 수이며
  cap 초과는 entry를 만들지 않는 typed no-op token이다. token close는 O(1)으로
  record count만 줄이고 lease release는 하지 않는다.
- release selector는 invalid name → `INVALID_LOCK_NAME`, 없음 → `NOT_REGISTERED`,
  둘 이상 → `AMBIGUOUS`, 같은 lock reservation → `ACTION_IN_PROGRESS` 순서로
  선형화한다. 유일한 record만 monotonic deadline과 action reservation을 가진다.
- pre-check는 `ownershipStatus()`를 한 번 호출한다. `NOT_HELD`/`UNKNOWN`은 각각
  no-mutation outcome이고 release callback에 진입하지 않는다. `HELD`일 때만
  `RELEASE_STARTED` CAS 후 `handle.release()`를 최대 한 번 호출하고,
  `NOT_HELD` post-check면 `RELEASED`, `HELD/UNKNOWN`이면
  `RELEASE_UNCONFIRMED`가 된다.
- RuntimeException은 pre-check `OWNERSHIP_UNKNOWN`, release `RELEASE_FAILED`,
  post-check `RELEASE_UNCONFIRMED`로 정제한다. `Error`는 finally 정리 후 caller에
  재전파하며 release 이후에는 quarantine한다. callback exception/lock name/
  identity를 result나 observer에 포함하지 않는다.
- scheduler admission은 action entry 시점부터 deadline을 계산한다. active+queue
  cap을 넘으면 즉시 `ACTION_ADMISSION_REJECTED`를 반환하고 callback은 호출하지
  않는다. submit race/rejection도 같은 typed outcome이며 caller thread에서
  callback을 실행하지 않는다.
- timeout이 release 시작 전이면 Future를 취소하고 `ACTION_TIMED_OUT(false)`를
  반환한다. release 시작 후면 `ACTION_TIMED_OUT(true)`를 즉시 반환하되 worker,
  registration, scheduler slot을 실제 terminalization/cleanupGrace까지 보존한다.
  `Future.cancel(true)`는 best effort이며 강제 thread stop/retry는 없다. worker가
  interruption을 무시하면 `NON_INTERRUPTIBLE` quarantine으로 남고 실제 Future
  종료 전 capacity를 회복하지 않는다.
- lifecycle은 `OPEN -> QUIESCING -> CLOSED`다. quiescing 이후 신규 register/release는
  `REGISTRY_CLOSED` no-mutation이며 기존 admitted worker만 drain 대상이다.
  `closeAndDrain()`은 closeTimeout 안에 terminal/quarantine 정리를 기다려 Boolean을
  반환하고, `close()`는 결과를 버린다. 등록된 lease를 임의로 release하거나
  application executor를 닫지 않는다.

**TDD 단계:**

- [ ] RED: fake `LeaderLeaseHandle`/latch fixture로 unique HELD success,
  duplicate/invalid/not-held/unknown, runtime exception, Error propagation,
  exact-one release/post-check을 작성한다.
- [ ] RED: same-lock in-progress, distinct-lock non-blocking mutex, active/queue
  overflow, caller-thread callback 금지, timeout-before/after-release,
  non-interruptible quarantine, registration cap/identity close race를 작성한다.
- [ ] RED: close race, closeAndDrain Boolean, sorted names, observer exception
  isolation, result/observation redaction을 작성한다.
- [ ] GREEN: store → scheduler admission → worker phase CAS → cleanup 순서로
  구현하고 scheduler regression을 통과시킨다.
- [ ] REFACTOR: callback helper와 deadline helper를 기존
  `MonotonicDeadline`/`LeaseCleanupReservation`/`ResidualLeaseRegistry`와
  재사용하고 새 abstraction을 중복 생성하지 않는다.

**검증:**

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests '*LeaderManagementActionRegistryTest' \
  --tests 'io.bluetape4k.leader.internal.LeaseOperationSchedulerTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과: 전체 PASS, slow callback 테스트에서 timeout 응답은 deadline 안에
돌아오며 `quarantinedCount()`와 scheduler outstanding은 실제 worker 종료 뒤에만
감소한다.

**커밋 경계:** `blocking management registry의 bounded release lifecycle을 구현한다`.

### 작업 3. Suspend registry와 cancellation parity

**생성/수정 파일:**

- `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderManagementActionRegistry.kt`
- `leader-core/src/test/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderManagementActionRegistryTest.kt`

**구현 계약:**

```kotlin
class SuspendLeaderManagementActionRegistry(
    observer: LeaderManagementActionObserver? = null,
    actionTimeout: kotlin.time.Duration = 5.seconds,
    cleanupGrace: kotlin.time.Duration = 30.seconds,
    maxInFlightActions: Int = 16,
    actionQueueCapacity: Int = 32,
    maxRegistrations: Int = 1_024,
    closeTimeout: kotlin.time.Duration = 5.seconds,
) : AutoCloseable {
    fun register(handle: SuspendLeaderLeaseHandle): LeaderManagementRegistration
    suspend fun release(lockName: String): LeaderManagementActionResult
    fun registeredLockNames(): List<String>
    fun quarantinedCount(): Int
    suspend fun closeAndDrain(): Boolean
    override fun close()
}
```

- registry-owned `SupervisorJob`와 `CoroutineScope`에서 worker를 실행하고 caller
  scope/job를 건드리지 않는다. blocking executor, `runBlocking`, `Future.get`를
  사용하지 않는다. queue cap은 `Mutex` 아래 즉시 admission CAS로 관리한다.
- `release()` entry에서 monotonic deadline을 만들고 admission을 기다리지 않는다.
  pre-check 전 caller cancellation은 mutation 없이 `CancellationException`을
  재전파한다. release/post-check 중 caller cancellation은 원래 cancellation을
  재전파하되 registry worker는 `NonCancellable` cleanupGrace/quarantine을 계속한다.
- timeout 전/후 mutation flag, ownership/runtime/Error mapping, same-lock action,
  registration identity, close drain은 blocking registry와 동일하게 유지한다.
- `close()`는 registry-owned SupervisorJob만 취소하고, `closeAndDrain()`은 bounded
  drain을 먼저 시도한 뒤 scope를 종료한다. close timeout 뒤 남은 worker는 실제
  coroutine completion 전까지 `quarantinedCount`와 admission slot을 유지한다.

**TDD 단계:**

- [ ] RED: `runTest` 또는 기존 `runSuspendIO` pattern으로 unique HELD, no-op
  outcomes, duplicate/action-in-progress, capacity rejection, exact-one callback을
  작성한다.
- [ ] RED: pre-check cancellation, release/post-check cancellation, timeout,
  NonCancellable cleanup, quarantine recovery, external application Job 보존,
  `runBlocking`/blocking API 정적 guard를 작성한다.
- [ ] GREEN: SupervisorJob worker와 Mutex admission을 구현하고 blocking truth table
  parity test를 통과시킨다.
- [ ] REFACTOR: virtual-time에 의존하는 `delay`보다 latch/deferred와 짧은 bounded
  deadline을 우선해 flaky test를 제거한다.

**검증:**

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests '*SuspendLeaderManagementActionRegistryTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과: suspend test PASS; caller job cancellation이 registry worker와
application scope를 취소하지 않고, worker completion 후에만 capacity/gauge가
회복된다.

**커밋 경계:** `suspend management registry의 cancellation 경계를 blocking과 맞춘다`.

### 작업 4. Micrometer observer와 quarantine metrics

**생성/수정 파일:**

- `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderManagementActionObserver.kt`
- `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt`
- `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderManagementActionObserverTest.kt`

**구현 계약:**

- `MicrometerLeaderManagementActionObserver(MeterRegistry)`는
  `LeaderManagementActionObserver`를 구현한다. counter 이름은
  `bluetape4k.leader.management.quarantine`, active gauge 이름은
  `bluetape4k.leader.management.quarantine.active`로 고정한다.
- tag key는 `reason`, `phase`, `surface`; value는 enum allow-list의 lowercase
  mapping만 사용한다. lock/actor/request/credential/token/exception은 meter
  name/tag/description에 넣지 않는다. dynamic tag 생성 API를 사용하지 않는다.
- quarantine event마다 counter를 증가시키고 `(reason, phase, surface)`별
  `AtomicInteger` gauge를 증가시킨다. registry가 실제 Future/coroutine 종료를
  알리는 terminal recovery event에서 gauge를 감소시킨다. observer exception은
  metric update와 registry result를 바꾸지 않는다.
- Spring default registry는 optional Micrometer class가 있을 때만 이 observer를
  연결한다. `leader-micrometer`가 없는 classpath에서는 action core가 정상 동작하고
  meter bean을 만들지 않는다. Ktor custom registry는 애플리케이션이 observer를
  명시적으로 주입한다.

**TDD 단계:**

- [ ] RED: `SimpleMeterRegistry`에서 네 reason, phase, surface의 meter identity,
  counter 증가, active gauge 증가/회복, unknown tag 거부, observer exception
  isolation test를 작성한다.
- [ ] GREEN: observer와 fixed-name constants를 구현한다.
- [ ] REFACTOR: 기존 `MicrometerNames`와 `LeaderMetricNames` convention을
  재사용하고 metric construction을 helper로 모은다.

**검증:**

```bash
./gradlew :bluetape4k-leader-micrometer:test \
  --tests '*MicrometerLeaderManagementActionObserverTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과: SimpleMeterRegistry에서 counter/gauge가 정확한 fixed tag set으로
관찰되고 recovery 후 active gauge가 0이 된다.

**커밋 경계:** `quarantine 상태를 저카디널리티 Micrometer 계약으로 계측한다`.

### 작업 5. Spring Actuator web action과 lifecycle

**생성/수정 파일:**

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderManagementActionProperties.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActionWebEndpoint.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionManagementActionAutoConfiguration.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderManagementActionLifecycle.kt`
- `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionManagementActionAutoConfigurationTest.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActionWebEndpointTest.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionManagementActionHttpTest.kt`
- 기존 `LeaderElectionStatusEndpointTest.kt`, `LeaderElectionActuatorHttpPathTest.kt`,
  `LeaderConfigurationMetadataTest.kt`의 회귀 assertions

**구현 계약:**

- `LeaderManagementActionProperties`는 `enabled=false`, `timeout=Duration.ofSeconds(5)`
  를 갖고 timeout을 30초 hard maximum 안에서 검증한다. 기존 `LeaderProperties`
  생성자/copy/ABI는 수정하지 않는다.
- 별도 `@AutoConfiguration(after = [LeaderElectionActuatorAutoConfiguration::class])`
  class를 imports에 등록한다. parent
  `management.endpoint.leaderElection.enabled=true`와 nested
  `management.endpoint.leaderElection.actions.enabled=true`가 모두 true일 때만
  action config가 활성화된다. parent가 false면 nested true여도 bean/registry/route가
  없어야 한다.
- `@ConditionalOnMissingBean(LeaderManagementActionRegistry::class)` default bean은
  property timeout과 core default caps를 사용한다. custom registry가 있으면 default
  registry와 library lifecycle bean을 만들지 않는다. default registry bean의
  destroy method는 `close()` 자동 호출과 lifecycle 순서 역전을 막도록 비활성화하고,
  `LeaderManagementActionLifecycle`이 `closeAndDrain()` 결과를 확인한 뒤 registry를
  닫는다. false면 sanitized warning/metric을 남기고 application shutdown을
  무한정 지연하지 않는다.
- `@WebEndpoint(id = "leaderElectionActions")`만 사용한다. `@Endpoint` annotation은
  사용하지 않는다. `@WriteOperation release(@Selector lockName: String)`은
  `WebEndpointResponse<LeaderManagementActionHttpResponse>`를 반환해 core HTTP
  mapping을 따른다. body allow-list는 `action`, `outcome`, `mutationAttempted` 세
  key뿐이며 exception/token/lock/leader 정보는 없다.
- 기존 `LeaderElectionStatusEndpoint @Endpoint(id="leaderElection")`, GET path,
  JMX status, public constructor/response descriptor는 그대로 둔다. JMX wildcard에도
  action endpoint가 나타나지 않는지 discoverer test로 증명한다. Spring Security나
  `SecurityFilterChain`은 자동 설치하지 않는다.
- optional `MeterRegistry`와 micrometer observer class가 동시에 있을 때만 default
  registry observer를 연결한다. custom observer/registry는 application ownership을
  유지한다.

**TDD 단계:**

- [ ] RED: ApplicationContextRunner로 기본값(no bean), parent false fail-closed,
  nested false status-only, both true action/default registry, custom registry
  override, timeout bound를 작성한다.
- [ ] RED: MockMvc/random-port HTTP test로 outcome별 status/body allow-list,
  exposure include 누락, JMX non-exposure, 기존 GET/status 회귀를 작성한다.
- [ ] RED: lifecycle test로 default registry drain-before-close와 custom registry
  non-close를 고정한다.
- [ ] GREEN: properties, auto-config, endpoint, lifecycle을 구현한다.
- [ ] REFACTOR: status endpoint 코드를 복제하지 않고 기존 state/registry selector를
  재사용하며, auto-config 조건을 imports 순서와 함께 최소화한다.

**검증:**

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests '*LeaderElectionManagementAction*' \
  --tests '*LeaderElectionActionWebEndpointTest' \
  --tests '*LeaderConfigurationMetadataTest' \
  --tests '*LeaderElectionStatusEndpointTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과: action opt-in/HTTP/JMX/lifecycle/metadata PASS, 기존 status tests PASS,
write operation은 `leaderElectionActions` exposure 없이는 404/non-exposed다.

**커밋 경계:** `Spring Actuator write action을 명시적 opt-in과 drain lifecycle 뒤에 둔다`.

### 작업 6. Ktor explicit action route와 graceful shutdown

**생성/수정 파일:**

- `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementActionRoute.kt`
- `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderManagementShutdown.kt`
- `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt`
- `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionManagementActionRouteTest.kt`
- `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderManagementShutdownTest.kt`
- 기존 `LeaderElectionManagementRouteTest.kt`, `LeaderElectionPluginTest.kt`
- `leader-ktor/build.gradle.kts`의 test-only `ktor-server-auth` dependency

**구현 계약:**

- `LeaderElectionPluginConfig`에 `managementActionRouteEnabled=false`, nullable
  `managementActionRegistry`, nullable `managementActionRoutePath`를 additive로
  추가한다. 기존 GET management config/default/registry/JSON은 수정하지 않는다.
- route extension은 `Route` receiver로 분리하고 action path default를
  `managementRoutePath.trimEnd('/') + "/actions"`로 계산한다.

  ```kotlin
  fun Route.leaderElectionManagementActionRoute(
      path: String = ...,
      registry: SuspendLeaderManagementActionRegistry = ...,
      authorize: suspend ApplicationCall.() -> Boolean,
  )
  ```

  `managementActionRouteEnabled=false`면 route를 설치하지 않거나 fail-closed하며,
  enabled인데 registry가 없으면 startup에서 명시적 configuration error를 낸다.
  route는 plugin install 시 자동 등록하지 않는다. 호출자는 반드시
  `authenticate("management") { route.leaderElectionManagementActionRoute(...) }`
  scope 안에서 명시적으로 설치한다.
- provider가 반환하는 unauthenticated `401`/principal `403`은 Ktor auth가 소유한다.
  `authorize` false는 `403 AUTHORIZATION_DENIED`, callback exception은
  `500 AUTHORIZATION_FAILED`로 정제하고 registry를 호출하지 않는다.
- POST `/{lockName}`는 selector를 `isManagementActionLockName`으로 검증한다.
  matcher가 `/`/`%2F`를 route로 매칭하지 않으면 404, 매칭된 hostile selector는
  400 `INVALID_LOCK_NAME`이다. registry result는 공통 HTTP mapping으로 status를
  계산하며 manual `ContentType.Application.Json` body key는 action/outcome/
  mutationAttempted뿐이다. `LeaderManagementRouteError`는 fixed code/message만
  가진 Serializable DTO다.
- route handler는 blocking release나 `runBlocking`을 사용하지 않는다. registry
  `release`가 caller cancellation을 재전파하면 route도 원래 cancellation을
  유지하고 worker cleanup은 계속된다.
- `LeaderManagementShutdown.kt`는 `ApplicationEngine` receiver helper를 제공한다.
  `closeAndDrain()`이 false여도 engine stop은 계속하되 sanitized metric/log를
  남긴다. `ApplicationStopping` listener와 외부 scope cancel은 구현하지 않는다.

**TDD 단계:**

- [ ] RED: explicit install 전 POST 404, config disabled, registry null fail-fast,
  canonical/custom path separation, existing GET regression을 작성한다.
- [ ] RED: real Ktor auth provider로 unauthenticated 401, principal 403, authorize
  false/exception 403/500 및 registry no-call을 작성한다.
- [ ] RED: all core outcomes HTTP matrix, no-body/JSON allow-list, hostile encoded
  selector 404 vs 400, raw secret/log redaction, shutdown ordering/caller scope
  preservation을 작성한다.
- [ ] GREEN: route, auth callback, manual JSON, engine helper와 test-only auth
  dependency를 구현한다.
- [ ] REFACTOR: 기존 `LeaderJsonSupport`와 `path` normalization helper를 재사용하고
  GET route 파일에 write logic을 섞지 않는다.

**검증:**

```bash
./gradlew :bluetape4k-leader-ktor:test \
  --tests '*LeaderElectionManagementActionRouteTest' \
  --tests '*LeaderManagementShutdownTest' \
  --tests '*LeaderElectionManagementRouteTest' \
  --tests '*LeaderElectionPluginTest' \
  --no-configuration-cache --max-workers=1
```

예상 결과: auth/path/outcome/lifecycle PASS, explicit route 밖 POST는 노출되지 않고
기존 GET route 테스트도 PASS한다.

**커밋 경계:** `Ktor management action을 application auth와 engine drain 뒤에 둔다`.

### 작업 7. README, unreleased manual draft, 운영 runbook

**생성/수정 파일:**

- `leader-core/README.md`, `leader-core/README.ko.md`
- `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`
- `leader-ktor/README.md`, `leader-ktor/README.ko.md`
- 필요 시 top-level `README.md`, `README.ko.md`에는 각 module/manual 링크만 추가
- six `docs/manual/drafts/2026-08-26-issue-532-management-action-*.{en,ko}.md`

**문서 내용:**

- core 문서는 registration token lifecycle, ownership proof, HELD pre-check,
  release/post-check, outcome truth table, timeout false/true 차이,
  application work를 취소하지 않는 예제를 설명한다. `runIfLeader`는 action registry
  대상이 아니며, group/strategic/runtime auto-registration이 없음을 명시한다.
- Spring 문서는 두 property가 모두 true여야 하는 조건, 별도
  `leaderElectionActions` exposure, `@WebEndpoint`/JMX non-write, Spring Security
  application ownership, custom registry lifecycle을 보여준다.
- Ktor 문서는 `authenticate("management")` 내부 explicit route, `authorize`
  callback, provider와 callback의 401/403/500 경계, canonical path와 encoded
  selector, `ApplicationEngine.stopLeaderManagementGracefully` 호출 순서를
  보여준다.
- operations 문서는 outcome→HTTP/retry matrix를 표로 싣는다. 기본 자동 retry는
  없고 `ACTION_TIMED_OUT(false)`는 worker terminalization 전 즉시 재호출하지
  않는다. `RELEASE_UNCONFIRMED`/`RELEASE_FAILED`를 성공으로 승격하지 않는다.
- quarantine runbook은 disable → bean/route 부재 확인 → auth/network canary →
  outcomes/quarantine counter/gauge 관찰 → 문제 시 disable/remove route →
  unconfirmed/quarantine 재시도 중지 → diagnostics 확인 → drain/replace 순서를
  고정한다. lock/actor/request/credential/exception을 문서 예제와 로그에 넣지
  않는다.
- draft 첫 문단은 `0.5.0` pinned release manual이 아니며 다음 release manifest
  승격 후 released API claim으로 바뀐다는 provenance를 명시한다. 기존 pinned
  manual six files는 건드리지 않는다.

**TDD/정적 검증:**

- [ ] EN/KO headings, API 이름, path, property, HTTP code, retry, lifecycle의
  parity를 `rg` 비교 목록으로 확인한다.
- [ ] `git diff --check`와
  `/Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs`
  를 실행해 terminology findings=0을 확인한다.
- [ ] draft links가 repository-relative 안전 경로인지 확인한다. manifest release
  inventory는 기존 pinned files만 대상으로 계속 PASS해야 한다.

**검증:**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  leader-core/README.md leader-core/README.ko.md \
  leader-spring-boot/README.md leader-spring-boot/README.ko.md \
  leader-ktor/README.md leader-ktor/README.ko.md \
  docs/manual/drafts/2026-08-26-issue-532-management-action-spring.en.md \
  docs/manual/drafts/2026-08-26-issue-532-management-action-spring.ko.md \
  docs/manual/drafts/2026-08-26-issue-532-management-action-ktor.en.md \
  docs/manual/drafts/2026-08-26-issue-532-management-action-ktor.ko.md \
  docs/manual/drafts/2026-08-26-issue-532-management-action-operations.en.md \
  docs/manual/drafts/2026-08-26-issue-532-management-action-operations.ko.md
```

예상 결과: diff check clean, terminology audit findings 0, draft provenance 경고
누락 없음.

**커밋 경계:** `Issue #532의 opt-in 운영 계약과 unreleased runbook을 문서화한다`.

### 작업 8. 통합 검증, ABI, workflow receipt와 delivery gate

**검증 순서:**

- [ ] worktree/branch/base/head와 변경 파일을 다시 읽고 unrelated dirty change가
  없는지 확인한다. 다른 worktree는 수정하거나 정리하지 않는다.
- [ ] core, micrometer, Spring, Ktor targeted test를 순서대로 실행한다. 한 단계가
  실패하면 downstream 실행을 멈추고 원인을 수정한 뒤 같은 명령을 재실행한다.
- [ ] 네 모듈 full test/check와 detekt를 실행한다. Testcontainers가 포함된 명령은
  `colima status`, `docker context show`, `docker info`를 먼저 확인하고 skip/실패를
  성공으로 세지 않는다.
- [ ] 기존 public ABI와 새 additive descriptor를 확인한다. 현재 repo task 계약에
  맞춰 `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0` 환경을 사용한다.
- [ ] manual inventory/manifest/release contract를 pinned 0.5.0 값으로 실행한다.
- [ ] CI fan-out/static validation과 exact head hosted run을 확인한다. stale SHA,
  path-filter skip, local-only pass는 remote PASS로 세지 않는다.
- [ ] 독립 read-only code review를 실행하고 HIGH/CRITICAL 또는 P0/P1 finding이
  있으면 수정 후 전체 targeted test와 review를 반복한다. 1인 개발자이므로
  human review는 N/A로 기록하되 독립 agent/reviewer evidence는 생략하지 않는다.
- [ ] PR 생성은 target repository/base/head와 승인된 plan을 다시 확인한 뒤 별도
  gate로 수행한다. merge는 fresh exact-head CI/review/metadata/linked issue를
  재확인하고 사용자의 별도 merge approval 후에만 한다.

**명령:**

```bash
./gradlew :bluetape4k-leader-core:test \
  :bluetape4k-leader-micrometer:test \
  :bluetape4k-leader-spring-boot:test \
  :bluetape4k-leader-ktor:test \
  --no-configuration-cache --max-workers=1

./gradlew :bluetape4k-leader-core:check \
  :bluetape4k-leader-micrometer:check \
  :bluetape4k-leader-spring-boot:check \
  :bluetape4k-leader-ktor:check \
  detekt --no-configuration-cache --max-workers=1

ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0 \
  ./gradlew checkBinaryCompatibility --no-configuration-cache --max-workers=1

./gradlew exportManualModuleInventory --no-configuration-cache --max-workers=1
MANUAL_REF="$(ruby -e 'require "yaml"; puts YAML.load_file("docs/manual/manifest.yaml")["releaseRef"]')"
MANUAL_SHA="$(ruby -e 'require "yaml"; puts YAML.load_file("docs/manual/manifest.yaml")["releaseCommit"]')"
ruby scripts/manual/release_inventory.rb "$MANUAL_REF" "$MANUAL_SHA" \
  build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb "$MANUAL_REF" "$MANUAL_SHA"
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e \
  'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'

git diff --check
```

예상 결과는 네 모듈 `BUILD SUCCESSFUL`, detekt/ABI/manual tests PASS, diff check
clean이다. 실패하거나 실행할 수 없는 검증은 PASS로 표시하지 않고 DoD의 known gap에
정확한 명령과 오류를 기록한다.

**workflow receipt:** 현재 `.bluetape` run id
`20260826T013743Z-2ba8e0a3`의 verify checksum/event count를 implementation
milestone마다 기록한다. 마지막에는 다음을 실행해 `ok=true`를 확인한다.

```bash
python3 /Users/debop/.codex/skills/bluetape-workflow/scripts/bluetape-flow.py \
  --state-root .bluetape verify --run-id 20260826T013743Z-2ba8e0a3
```

**커밋 경계:** source/doc commit 이후 별도의 implementation verification commit만
허용한다. 모든 commit은 Lore intent line과 `Tested`/`Not-tested` trailer를 실제
증거에 맞춰 작성한다.

## 실패 시 중단 조건

다음 중 하나라도 발생하면 새 adapter나 문서/PR 단계로 진행하지 않고 해당 단계에서
멈춘다.

- public contract test가 observation reason/cardinality, mutation flag, serialization
  allow-list를 결정하지 못한다.
- ownership `UNKNOWN`, release/post-check Error, timeout 후 non-interruptible worker,
  close race의 reservation/counter 수명이 증명되지 않는다.
- active+queue cap 초과가 caller thread callback이나 무제한 대기를 허용한다.
- suspend cancellation이 caller scope 또는 application scope를 취소하거나
  blocking executor/runBlocking을 사용한다.
- Spring action이 parent endpoint disabled 상태에서 생성되거나 기존 status/JMX
  descriptor/path를 바꾼다.
- Ktor route가 explicit auth scope 밖에서 노출되거나 callback false/exception 전에
  registry mutation을 시작한다.
- metric/log/JSON에 raw lock, actor, credential, token, exception text가 유입된다.
- pinned manual validation이 0.5.0 immutable inventory에 대해 실패하거나 draft가
  release claim으로 오인될 수 있다.
- targeted/full test, ABI, detekt, exact-head CI, 독립 review 중 하나라도 fresh
  evidence 없이 남는다.

## 실행 선택

계획 승인 후 `$subagent-driven-development`(권장)으로 작업 1~8을 작은 커밋 단위로
실행하고 각 단계마다 leader가 검증한다. 1인 개발자 환경에서 inline 실행을
선택하더라도 동일한 파일 경계, TDD 순서, verification gate, human-review N/A
기록을 유지한다. 구현/PR/merge는 각각 별도 approval gate로 취급한다.
