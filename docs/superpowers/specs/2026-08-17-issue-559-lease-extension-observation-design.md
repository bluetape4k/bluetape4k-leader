# Issue #559 lease-extension observation hook 설계 사양

## 문서 상태

- 이슈: [#559](https://github.com/bluetape4k/bluetape4k-leader/issues/559)
- Epic: [#699](https://github.com/bluetape4k/bluetape4k-leader/issues/699)
- Train: `OBS-02`
- 선행 train: OBS-01 / [#533](https://github.com/bluetape4k/bluetape4k-leader/issues/533), [PR #718](https://github.com/bluetape4k/bluetape4k-leader/pull/718) — `42f42ffa6df4d2906a4312fc9b7acb14d75439e9`에 병합됨
- 후행 train: OBS-03 / [#535](https://github.com/bluetape4k/bluetape4k-leader/issues/535)
- 기준 브랜치: `develop`
- 작업 브랜치: `feat/obs-02-core-contract`
- 작성일: 2026-08-17
- 상태: 사양 승인 완료; PR1 core contract 구현 완료

이 문서는 구현 전에 계약, 경계, 관측 의미를 고정하기 위한 Type-A 설계 사양이다. 이 문서의 승인 전에는 구현 계획 파일과 production code를 변경하지 않는다.

## 1. 문제와 목표

Issue #529는 acquire/execution terminal callback과 leader election lifecycle observation을 제공하지만, lease extension은 의도적으로 제외했다. 현재 `LockExtender`와 `LeaderLeaseAutoExtender`는 동일한 상세 결과를 계산하면서도 instrumentation에 전달할 framework-neutral hook이 없다.

이번 train의 목표는 다음 네 경로를 하나의 bounded contract로 관찰하는 것이다.

1. blocking `LockExtender.extendActiveLock(...)`와 detailed 변형
2. suspend `LockExtender.extendActiveLockSuspend(...)`와 detailed 변형
3. 동일한 `ExtendDelegate`를 사용하는 blocking watchdog auto-extend
4. 동일한 delegate contract를 사용하는 suspend watchdog auto-extend

관찰은 lease ownership을 결정하거나 변경하지 않는다. observation callback이 실패해도 lock extension의 반환값, backend error 분류, coroutine cancellation, watchdog 종료 판단은 기존 의미를 그대로 유지해야 한다.

## 2. 현재 구현 근거

| 근거 | 현재 계약 | 설계 제약 |
| --- | --- | --- |
| `leader-core/.../LockExtender.kt` | blocking/suspend detailed 경로가 `ExtendOutcome`을 반환하고 `Extended`일 때만 `lastExtendDeadline`을 갱신한다. | hook은 delegate 호출과 deadline 갱신의 순서를 바꾸지 않는다. |
| `leader-core/.../ExtendOutcome.kt` | `Extended`, `NotHeld`, `WrongThread`, `BackendError`가 공통 vocabulary다. | blocking/suspend/watchdog 모두 같은 outcome 이름을 사용한다. |
| `leader-core/.../internal/ExtendDelegate.kt` | watchdog와 handle이 같은 delegate reference를 사용한다. | backend별 wrapper나 중복 extend logic을 만들지 않는다. |
| `leader-core/.../LeaderLeaseAutoExtender.kt` | blocking/suspend `start` overload가 delegate를 호출하고 transient/non-transient/fatal 정책을 적용한다. | observation은 `start`의 기존 결과 처리보다 바깥에서 terminal attempt를 기록한다. |
| `leader-core/.../LeaderLockHandle.kt` | `Real`에 `lockName`, `auditLeaderId`, `extendDelegate`가 함께 있고 `FailOpen`도 별도 상태다. | 실제 handle context만 identity source로 사용한다. |
| `leader-core/.../internal/LockStateHolder.kt`, `coroutines/LockHandleElement.kt` | blocking은 thread-local stack, suspend는 coroutine context에서 active handle을 찾는다. | 두 lookup 경로가 모두 hook에 연결되어야 한다. |
| `leader-core/.../LeaderElectionListener.kt` | election lifecycle listener는 `Elected`, `Revoked`, `Skipped`에 집중한다. | lease extension을 기존 election listener에 추가해 lifecycle domain을 결합하지 않는다. |
| `leader-micrometer/.../MicrometerObservationLeaderAopMetricsRecorder.kt` | observation name과 low/high-cardinality tag 정책이 이미 존재한다. | 새 observer도 동일한 redaction과 bounded tag 원칙을 재사용한다. |

## 3. 선택한 아키텍처

### 3.1 framework-neutral core observer registry

`leader-core`에 다음 additive public contract를 둔다.

- `LeaderLeaseExtensionObserver`: 한 번의 extension attempt가 끝난 뒤 `onExtension(event)`을 받는 functional interface
- `LeaderLeaseExtensionObservers`: 기본 process-local registry의 등록·해제·publish를 제공하는 singleton facade
- `LeaderLeaseExtensionEvent`: source, execution model, outcome, elapsed time, 선택적 active-handle context를 담는 immutable event
- `LeaderLeaseExtensionSource`: `USER`, `WATCHDOG`
- `LeaderLeaseExtensionExecution`: `BLOCKING`, `SUSPEND`
- `LeaderLeaseExtensionContext`: `lockName`과 `auditLeaderId`를 포함하는 선택적 context

기본 registry는 `CopyOnWriteArrayList` 기반 snapshot을 사용한다. 읽기(각 extension attempt의 publish)가 등록·해제보다 압도적으로 많고, observer 목록을 순회하는 동안 등록/해제가 발생해도 현재 snapshot의 일관성을 유지해야 하기 때문이다. 이 train은 전역 process-local registry 하나만 제공한다. 별도 registry를 `LockExtender` 또는 watchdog에 주입하는 계약은 범위에서 제외한다.

등록 메서드는 idempotent `AutoCloseable`을 반환한다. 동일 observer를 여러 번 등록할 수 있으며 각 registration handle은 자기 entry만 소유한다. `close()`는 해당 entry를 한 번만 제거하고, 이미 닫힌 handle을 다시 닫아도 예외를 내지 않는다. 명시적 `removeObserver`는 동일 object identity를 가진 entry를 모두 제거하고 실제 변경 여부를 Boolean으로 반환한다.

core registry 자체는 동일 observer의 중복 등록을 deduplicate하지 않는다. 중복 등록이 필요한 직접 사용자는 각 handle을 독립적으로 소유한다. Spring auto-configuration만 `ObservationRegistry` identity별 shared registration/ref-count를 제공한다. 이 manager는 `internal object`인 process-global singleton이며 `IdentityHashMap<ObservationRegistry, Entry>`와 하나의 lock으로 acquire/release를 직렬화한다. 동일한 `ObservationRegistry`를 공유하는 parent/child 또는 병렬 `ApplicationContext`는 같은 Micrometer observer와 core registration 하나를 공유하고, context별 `AutoCloseable` handle이 ref-count를 감소시킨다. acquire, ref-count 증가, last-close 판정, core registration close, identity-map 제거의 각 단계가 같은 lock 안에서 선형화되므로 create-vs-last-close race에서 double registration이나 조기 close가 발생하지 않는다. 마지막 handle이 닫힐 때만 core registration을 닫고 identity map entry를 제거한다. 동일 registry에 현재 entry와 다른 `LeaderObservationOptions`가 요청되면 추가 observer를 등록하거나 첫 옵션을 조용히 재사용하지 않고 `IllegalStateException`으로 fail-fast한다. 이 예외는 redaction 정책이 약화되는 것을 막으며, context destroy 이후 registry/handle에 대한 strong reference를 보존하지 않는다.

테스트와 컨테이너 lifecycle은 등록 handle을 `use` 또는 명시적 `close()`로 정리해 전역 상태를 격리한다. Spring bean destroy 시에도 같은 handle을 닫는다. dispatcher가 overflow로 버린 observer delivery 수는 process-local `droppedCount()`로 누적하며, warning은 registration별로 rate-limit한다.

Kotlin/JVM public surface는 다음으로 고정한다.

| 선언 | JVM 계약 | 의미 |
| --- | --- | --- |
| `fun interface LeaderLeaseExtensionObserver { fun onExtension(event: LeaderLeaseExtensionEvent) }` | public SAM, non-null observer/event | callback은 event를 수정하지 않는다. |
| `class LeaderLeaseExtensionContext(val lockName: String, val auditLeaderId: String?)` | public immutable, deliberately non-`Serializable` value type with explicit `equals`/`hashCode`/redacted `toString` | active `Real` 또는 `FailOpen` handle에서만 만들며 `auditLeaderId`는 `Real`에서만 사용한다. data-class generated API와 serialization contract는 노출하지 않는다. `toString()`은 lock/leader identity를 출력하지 않는다. |
| `class LeaderLeaseExtensionEvent(val source: LeaderLeaseExtensionSource, val execution: LeaderLeaseExtensionExecution, val outcome: ExtendOutcome, val elapsedNanos: Long, val context: LeaderLeaseExtensionContext?)` | public immutable five-argument, deliberately non-`Serializable` value type with explicit `equals`/`hashCode`/redacted `toString` | `outcome`은 기존 `ExtendOutcome` reference이며 callback 동안만 사용한다. data-class `copy`/`componentN`과 serialization/long-term binary contract를 만들지 않는다. `toString()`은 bounded source/execution/outcome 이름만 출력하고 exception details, token, lock/leader identity를 출력하지 않는다. |
| `object LeaderLeaseExtensionObservers { @JvmStatic fun addObserver(observer: LeaderLeaseExtensionObserver): AutoCloseable }` | public static facade method `LeaderLeaseExtensionObservers.addObserver(LeaderLeaseExtensionObserver)` | registration token을 반환하며 `null`을 받지 않는다. member-extension receiver를 사용하지 않는다. |
| `object LeaderLeaseExtensionObservers { @JvmStatic fun removeObserver(observer: LeaderLeaseExtensionObserver): Boolean }` | public static facade method `LeaderLeaseExtensionObservers.removeObserver(LeaderLeaseExtensionObserver)` | 동일 object identity의 registration entry를 모두 제거한다. member-extension receiver를 사용하지 않는다. |
| `object LeaderLeaseExtensionObservers { @JvmStatic fun droppedCount(): Long }` | public static read-only diagnostic `LeaderLeaseExtensionObservers.droppedCount()` | admission 실패로 누락된 observer delivery 누적 수를 반환한다. reset은 제공하지 않는다. |
| `@JvmSynthetic internal fun hasObservers(): Boolean` (object member) | Java source에서 숨기는 internal bridge; public Java contract 아님 | extension caller가 event allocation 전에 관찰자 유무를 확인한다. |
| `@JvmSynthetic internal fun publish(event: LeaderLeaseExtensionEvent)` (object member) | Java source에서 숨기는 internal bridge; public Java contract 아님 | `LockExtender`와 `LeaderLeaseAutoExtender`만 terminal event를 publish한다. |

`LeaderLeaseExtensionEvent` 생성자는 `source`, `execution`, `outcome`, `elapsedNanos`, nullable `context`를 받는 다섯 인자 immutable value type으로 고정한다. 두 value type은 일반 final class이며 `Serializable`과 `serialVersionUID`를 제공하지 않고, 명시적 `equals`/`hashCode`와 redacted `toString`만 유지한다. `Context.toString()`은 고정된 타입명만 출력하고 lock/leader identity를 출력하지 않는다. `Event.toString()`은 bounded source/execution/outcome 이름만 출력하고 exception message/stack, raw backend payload, token, lock/leader identity를 출력하지 않는다. 따라서 Kotlin data-class `copy`/`componentN` generated JVM method는 public contract에 생기지 않는다. `@JvmStatic` facade는 기존 `LockExtender`/watchdog JVM descriptor를 변경하지 않는다. callback task는 일반 `Exception`을 격리하고 warning에는 callback class의 안전한 이름만 rate-limit해 기록한다. `Error` 계열(`VirtualMachineError`, `ThreadDeath`, `LinkageError` 포함)은 callback task 밖으로 재전파해 fatal 상태를 숨기지 않으며, permit 반환은 `finally`에서 보장한다.

`hasObservers`와 `publish`는 `@JvmSynthetic internal` bridge다. Kotlin internal의 JVM bytecode method가 reflection에 보일 수 있고 synthetic method가 runtime public으로 남을 수 있다는 점은 인정한다. `@JvmSynthetic`으로 Java source 호출과 공개 Java API 사용을 막되 security boundary로 사용하지 않는다. 외부에서 event를 직접 구성해도 publish bridge를 거치지 않으면 dispatch되지 않는다. ABI fixture는 두 bridge의 `ACC_SYNTHETIC`와 Java compile 불가를 확인하고, production caller가 core boundary 네 곳으로 제한되는지 source scan한다.

### 3.1.1 callback dispatch와 latency 경계

observer는 lease renewal critical path에서 임의의 사용자 코드를 실행하지 않는다. 각 blocking/suspend detailed boundary와 watchdog terminal boundary는 다음과 같이 `hasObservers()`를 먼저 확인해 observer가 없을 때 event/context와 timing 객체를 할당하지 않고 기존 결과를 반환한다: `if (LeaderLeaseExtensionObservers.hasObservers()) LeaderLeaseExtensionObservers.publish(LeaderLeaseExtensionEvent(...))`. `publish(event)`는 이 guard 뒤에만 호출되는 internal bridge이며, 빈 snapshot을 다시 확인해 즉시 반환할 수 있다. `hasObservers()`는 최적화 힌트일 뿐 delivery membership의 선형화 지점이 아니다. add/remove는 `CopyOnWriteArrayList`의 현재 배열 reference 교체로 선형화하고, `publish`는 observer snapshot 배열 reference를 읽는 순간 해당 attempt의 delivery membership을 선형화한다. 따라서 guard 직후 등록된 observer는 해당 attempt를 놓칠 수 있고, guard 직후 모두 제거된 observer는 불필요한 event allocation 뒤 빈 snapshot을 받을 수 있으며, 이미 snapshot/admission된 task에는 close가 소급되지 않는다. 이 race와 no-order 의미는 계약상 허용한다. observer가 있으면 event snapshot을 만든 뒤 bounded virtual-thread dispatcher에 non-blocking `tryAcquire`로 callback 작업을 제출한다. 전체 상한은 `MAX_IN_FLIGHT = 1024`, registration별 상한은 `MAX_IN_FLIGHT_PER_OBSERVER = 256`이다. registration별 상한을 넘으면 해당 observer event만 버리고, 전체 상한을 넘으면 현재 event의 남은 observer delivery만 버린다. 두 경우 모두 `droppedCount`를 atomic하게 증가시키고 registration별 `AtomicLong lastWarningNanos`를 사용해 최대 초당 한 번만 coalesced warning을 남기며 extension 결과를 기다리거나 재시도하지 않는다. admission 실패 경로는 warning appender를 기다리지 않고 즉시 반환한다. 이 drop은 관측 손실일 뿐 ownership/renewal 결과가 아니다.

event snapshot의 각 registration entry는 하나의 callback task와 registration permit을 가진다. 한 observer의 admission 실패는 같은 event의 다른 observer task를 차단하지 않으며, 전체 permit이 고갈된 경우에만 남은 entry delivery를 건너뛴다.

callback 예외는 dispatcher task 안에서 격리한다. callback은 bounded/non-blocking observer 계약을 따라야 하며, 임의 callback을 강제로 중단하는 timeout은 제공하지 않는다. 영구 대기 callback은 자기 registration의 256 permits 중 하나를 점유하지만 전체 process 상한은 넘지 않는다. callback `finally`에서 registration/global permit을 반드시 반환한다. `publish`가 observer snapshot을 만든 뒤 admission에 성공한 event task는 registration handle을 닫은 뒤에도 실행될 수 있다. `close()`는 이후 snapshot에서만 entry를 제거하며 이미 accepted된 task를 취소하거나 강제 중단하지 않는다. callback ordering과 drain 보장은 계약에 포함하지 않는다. process-local dispatcher는 library shutdown과 독립적인 daemon/virtual-thread 수명으로 동작하며, `LeaderLeaseAutoExtender.shutdown()`이 observer dispatcher까지 종료시키지는 않는다. virtual-thread 시작 실패도 admission permit을 `finally`에서 반환하고 `droppedCount`에 반영한다.

### 3.2 event contract

`LeaderLeaseExtensionEvent`는 다음 필드를 가진다.

| 필드 | 의미 | 공개/보호 규칙 |
| --- | --- | --- |
| `source` | 사용자 호출인지 watchdog tick인지 구분한다. | bounded enum이며 tag로 직접 사용 가능하다. |
| `execution` | blocking인지 suspend인지 구분한다. | bounded enum이며 `ExtendOutcome`과 함께 parity를 검증한다. |
| `outcome` | 실제 extension delegate의 `ExtendOutcome`이다. | 기존 detailed vocabulary를 재사용하고 새 결과 종류를 만들지 않는다. |
| `elapsedNanos` | delegate 호출 전후의 monotonic elapsed time이다. | 음수가 될 수 없으며 wall-clock timestamp로 ownership을 추론하지 않는다. |
| `context` | active handle에서 얻은 `LeaderLeaseExtensionContext`다. | user event라도 active handle이 없으면 `null`; watchdog event는 OBS-02에서 항상 `null`이며 이름/ID를 추정하지 않는다. |

`Extended.observedExpireAt`는 outcome 안의 backend 관측값을 그대로 유지한다. registry는 이를 다시 계산하거나 `Instant.now()`로 바꾸지 않는다.

`BackendError.cause`는 기존 detailed API 계약을 보존하기 위해 event에서 기존 outcome의 참조로 접근할 수 있다. event가 cause의 message, stack trace, backend payload를 복사하거나 직렬화하지는 않으며, custom observer는 callback 안에서만 이를 볼 수 있다. 기본 observer와 Micrometer tag는 exception message, stack trace, token, raw identity를 기록하지 않는다. exception 상세를 기록하는 것은 명시적인 opt-in 옵션에서만 허용한다.

### 3.3 LockExtender 연결

observation boundary는 private delegate helper가 아니라 public detailed entrypoint 전체를 감싼다. 즉 blocking의 unnamed/named detailed 두 경로와 suspend의 unnamed/named detailed 두 경로가 모두 lookup 조기 반환을 포함해 한 번만 관찰된다. Boolean API는 detailed API를 호출하므로 별도 event를 publish하지 않는다. `extendDetailedInternal`/`extendDetailedSuspendInternal`은 active `Real` delegate 결과를 전달하는 내부 단계로만 남긴다.

1. public entrypoint에서 active handle lookup를 수행한다.
2. handle이 없거나 이름이 일치하지 않으면 기존 `NotHeld`를 만들고 context `null`, `elapsedNanos=0`인 user event를 publish한다.
3. `FailOpen`이면 기존 `NotHeld` 의미를 유지하고 lock name만 context에 넣으며 leader ID는 넣지 않는다.
4. active `Real`이면 실제 `Real.extend` 또는 `Real.extendSuspend`를 호출한다.
5. `Extended`인 경우 기존과 동일하게 `lastExtendDeadline`을 갱신한다.
6. delegate 호출이 반환한 뒤에만 `LeaderLeaseExtensionEvent(source=USER, execution=...)`을 publish한다.
7. observer callback의 일반 `Exception`은 별도 dispatcher에서 기록 후 무시하고, `Error`는 callback task 밖으로 재전파한다.
8. 반환값과 예외 전파는 기존 API와 동일하게 유지한다.

unexpected raw exception matrix는 다음으로 고정한다.

| 경로 | delegate/lookup 결과 | event | caller 결과 |
| --- | --- | --- | --- |
| active delegate | `ExtendOutcome` 반환 | 동일 outcome event | 기존 outcome 반환 |
| active delegate | `CancellationException` | 없음 | 예외 재전파 |
| active delegate | 다른 `Exception` | 관찰 전용 `BackendError(exception)` event | 원래 exception 재전파 |
| active delegate | `Error` | 없음 | 원래 `Error` 재전파 |
| outside scope/name mismatch | lookup 실패 | `NotHeld` event, context `null`, elapsed `0` | `NotHeld`/Boolean `false` |

active `Real` handle에서는 `lockName`과 `auditLeaderId`를 context source로 사용한다. `FailOpen`은 `lockName`만 제공할 수 있고 ownership을 의미하는 leader ID는 제공하지 않는다. active scope 밖 호출은 실제 active context가 없으므로 context를 `null`로 두고, API가 반환하는 `NotHeld`를 하나의 terminal user event로 기록한다. 이 event의 `elapsedNanos`는 delegate를 호출하지 않은 경로임을 반영해 `0`으로 둔다. 어느 경우에도 존재하지 않는 lock/leader identity를 만들어서는 안 된다.

### 3.4 watchdog 연결

`LeaderLeaseAutoExtender`의 기존 blocking/suspend `start` overload는 유지한다. 각 tick에서 같은 `ExtendDelegate` 호출 결과를 얻은 직후 다음 event를 publish한다.

- blocking overload: `source=WATCHDOG`, `execution=BLOCKING`
- suspend overload: `source=WATCHDOG`, `execution=SUSPEND`

watchdog context는 delegate의 `hashCode`, lock-name 추정, node ID 추론으로 만들지 않는다. OBS-02의 기존 overload에는 context 인자를 추가하지 않으며 watchdog event의 context는 항상 `null`이다. 실제 handle context가 필요한 별도 API는 후속 이슈에서 명시적인 source/ABI 검토 후 다룬다.

`scheduleWithFixedDelay` 자체가 `RejectedExecutionException`을 던지는 scheduler admission 실패는 delegate invocation 전이므로 event를 만들지 않고 기존 `NoopCloseable` 반환 및 suspend `scope.cancel()` 동작을 유지한다. 반대로 이미 호출한 delegate가 `RejectedExecutionException`을 던지면 일반 delegate 예외와 같은 terminal `BackendError(exception)` watchdog event를 먼저 publish한 뒤 기존 scheduler-cancel/stop 동작을 유지한다. 이 두 rejection 경계를 blocking/suspend 각각에서 별도 contract test로 고정한다.

이 경계는 현재 production source의 56개 watchdog 호출부(44개 파일)를 backend별로 재작성하지 않고도 동일한 observer contract를 적용한다. 사용자와 watchdog 호출이 실제로 경쟁하면 각 실제 delegate invocation이 하나의 event가 되며, source가 다르므로 observer가 임의로 중복 제거하지 않는다.

### 3.5 cancellation, no-op, fail-open

- suspend delegate가 `CancellationException`을 던지면 cancellation을 삼키거나 `BackendError`로 바꾸지 않는다. 기존 structured concurrency를 보존하고 exception을 재전파한다. 취소되어 완료되지 않은 시도에는 성공/실패 `ExtendOutcome` event를 만들지 않는다.
- scheduler admission 전의 `RejectedExecutionException`은 기존 watchdog scheduler shutdown 처리와 동일하게 유지하며 extension event를 기록하지 않는다. 이미 호출한 delegate가 던진 `RejectedExecutionException`은 `BackendError(exception)` watchdog event를 기록한 뒤 기존 cancel/stop 동작을 유지한다.
- observer가 `CancellationException`을 포함한 예외를 던져도 extension 호출자의 cancellation/결과를 변경하지 않는다. callback은 별도 dispatcher task에서 실행되므로 registry는 callback 예외를 격리한다.
- `LockExtender` fail-open/no-op은 `NotHeld`로만 관찰하고 `Extended` 또는 leader identity를 거짓으로 보고하지 않는다. active scope 밖의 `NotHeld`도 같은 규칙으로 기록한다.

## 4. Micrometer wiring

`leader-micrometer`에 public implementation class `io.bluetape4k.leader.micrometer.MicrometerObservationLeaderLeaseExtensionObserver`를 추가하고, `leader-spring-boot`의 기존 `LeaderObservationAutoConfiguration`에 조건부 registration wiring을 추가한다. 구현체의 exact constructor는 `MicrometerObservationLeaderLeaseExtensionObserver(registry: ObservationRegistry, options: LeaderObservationOptions = LeaderObservationOptions())`로 고정한다. 이 구현체 외에 새로운 public observer SPI나 public metric constant를 추가하지 않는다.

### 4.1 observation 의미

각 event는 span/current scope가 아니라 하나의 terminal extension attempt다. Micrometer observer는 callback task 안에서 Observation을 시작하고 종료하며, callback이 반환된 뒤에도 lock ownership을 유지하거나 변경하지 않는다.

기본 observation name은 `bluetape4k.leader.lease.extension`으로 고정한다. OBS-02의 Micrometer observer는 delegate elapsed time을 별도 tag로 내보내지 않고 outcome/source/execution만 terminal observation에 기록한다. event의 `elapsedNanos`는 다른 observer가 사용할 수 있는 monotonic 측정값으로만 제공한다. observation name과 `source`, `execution`, `outcome`, `result` tag 이름/값 상수는 observer 구현 파일의 `internal` 또는 `private` 범위에 두며 public constant API로 노출하지 않는다. low-cardinality 값은 다음 네 가지로 제한한다.

- `source`: `user`, `watchdog`
- `execution`: `blocking`, `suspend`
- `outcome`: `extended`, `not_held`, `wrong_thread`, `backend_error`
- `result`: `success`, `skipped`, `error`

`result` mapping은 다음으로 고정한다.

| `ExtendOutcome` | `outcome` tag | `result` tag |
| --- | --- | --- |
| `Extended` | `extended` | `success` |
| `NotHeld` | `not_held` | `skipped` |
| `WrongThread` | `wrong_thread` | `error` |
| `BackendError` | `backend_error` | `error` |

직접 `LockExtender` delegate가 `CancellationException`을 던지면 event를 만들지 않고 예외를 재전파한다. 다른 `Exception`은 관찰 전용 `BackendError` event와 `result=error`를 남긴 뒤 원래 exception을 재전파한다. `Error`와 delegate 호출 전 scheduler `RejectedExecutionException`은 event를 만들지 않는다. watchdog이 delegate 호출 중 얻은 `BackendError` 또는 delegate-thrown `RejectedExecutionException`은 기존 retry/stop 정책을 적용하면서 위 표대로 관찰한다.

`BackendErrorKind`나 예외 종류를 low-cardinality tag에 추가할 때는 bounded enum으로 사전 정의하고, raw class name을 기본 tag로 사용하지 않는다. 이번 구현의 기본 acceptance는 위 네 tag로 한정해 cardinality를 예측 가능하게 하는 것이다.

기존 `LeaderObservationOptions`의 primary/secondary constructor shape와 `tagOptions`를 포함한 현재 옵션은 변경하지 않고, 현재 instance를 새 observer에 그대로 전달해 다음 정책을 재사용한다. constructor, `copy`, `copy$default`, `componentN` JVM descriptor도 유지한다.

- `includeLockName=false` 기본값: lock name은 기록하지 않는다.
- `includeLeaderId=false` 기본값: audit leader ID는 기록하지 않는다.
- `includeExceptionDetails=false` 기본값: exception message/stack trace를 기록하지 않는다.
- opt-in 시에도 lock/leader 값은 high-cardinality field로만 둔다.

Micrometer `ObservationRegistry.NOOP`와 registry 미설정 환경에서는 observer bean을 등록하지 않는다. core facade도 observer가 없으면 zero-allocation fast path를 사용해 extension latency나 결과를 바꾸지 않는다.

### 4.2 auto-configuration 경계

기존 auto-configuration 순서를 유지한다.

```text
LeaderElectionAutoConfiguration
LeaderAopFactoryAutoConfiguration
LeaderMicrometerAutoConfiguration
LeaderObservationAutoConfiguration
LeaderAopAutoConfiguration
```

`ObservationRegistry`가 없거나 `registry.isNoop`이거나 observation property가 꺼져 있으면 lease-extension observer bean을 만들지 않는다. `ObservationRegistryNotNoopCondition`이 실제 registry의 `isNoop`를 판정한다. 정상 registry에서 `LeaderObservationAutoConfiguration.leaseExtensionObserverRegistration(...)`은 `@Bean(destroyMethod = "close")`로 context별 `AutoCloseable` registration handle 하나만 소유하며, observer 객체 자체는 Spring bean으로 노출하지 않는다. process-global `LeaseExtensionObservationRegistrationManager`는 registry identity별 core registration 하나와 ref-count를 하나의 lock으로 선형화한다. context destroy는 자기 handle만 닫고 마지막 handle에서만 core registration을 닫으며, process-local dispatcher의 수명은 Spring context와 독립적이다. 동일 registry의 parent/child·병렬 context에서도 callback은 event당 한 번만 Micrometer observer에 전달되고, 모든 context가 닫힌 뒤 manager가 registry/handle strong reference를 보존하지 않아야 한다. 동일 registry의 옵션이 다르면 auto-configuration은 `IllegalStateException`으로 fail-fast하며 observer를 추가 등록하지 않는다. 사용자가 직접 registry를 등록하면 Spring auto-configuration을 거치지 않고도 core facade에 observer를 등록할 수 있으며, 이 직접 등록은 Spring shared-registration manager의 dedup 범위에 포함되지 않는다.

## 5. failure mode와 완화책

| failure mode | 잘못된 결과 | 완화책과 검증 |
| --- | --- | --- |
| observer callback이 예외를 던짐 | lock extension 결과가 바뀌거나 watchdog이 중단됨 | COW snapshot dispatch와 callback별 격리. observer 예외를 warning으로 기록하고 원래 결과를 반환한다. |
| observer callback이 느리거나 영구 대기함 | watchdog tick이 callback을 기다려 다음 renewal이 늦어짐 | bounded virtual-thread dispatcher와 `MAX_IN_FLIGHT` non-blocking admission. 상한 초과 event는 drop하고 renewal을 기다리지 않는다. |
| callback이 close 이후에도 실행 중임 | Spring context가 닫힌 뒤 observer resource가 유지됨 | close는 이후 snapshot에서만 제거하고 accepted task는 permit `finally` 후 종료한다. observer는 bounded/non-blocking callback 계약을 지켜야 하며 강제 interrupt/drain은 제공하지 않는다. |
| 한 observer가 admission permit을 독점함 | 다른 observer의 event가 전역 상한 때문에 함께 drop됨 | registration별 `MAX_IN_FLIGHT_PER_OBSERVER`와 전체 `MAX_IN_FLIGHT`를 분리하고 drop count/warning을 observer 단위로 기록한다. |
| 등록 handle을 닫지 않음 | process-local registry가 listener를 영구 참조해 memory leak 발생 | `AutoCloseable` 반환, idempotent close, Spring bean destroy 시 close, add/remove/close 반복 테스트. |
| user와 watchdog tick이 동시에 실행됨 | 중복 event를 잘못 합치거나 ownership을 거짓으로 보고함 | 실제 delegate invocation마다 하나의 event를 기록하고 `source`로 구분한다. registry에 dedup state를 두지 않는다. |
| raw lock/leader identity를 기본 tag로 사용함 | Micrometer time series 폭발과 민감정보 노출 | bounded low-cardinality 기본값, identity는 opt-in high-cardinality, 기존 redaction 정책 재사용. |
| delegate/hashCode/node ID로 ownership 추정 | 다른 lease의 leader ID 또는 lock name을 보고함 | active handle 또는 명시적 context만 허용하고 watchdog 기본 context는 `null`이다. |
| `BackendError`를 일반 실패로 평탄화 | 기존 retry/stop 정책과 상세 API가 손상됨 | event는 원래 `ExtendOutcome`을 유지하고 watchdog classifier/stop 정책은 기존 코드에서 계속 수행한다. |
| suspend cancellation을 삼킴 | structured concurrency 위반과 작업 지연 | `CancellationException`은 재전파하고 완료되지 않은 attempt는 outcome event를 만들지 않는다. |
| observer registry가 extension보다 먼저 상태를 변경함 | `lastExtendDeadline` 또는 반환 결과 불일치 | delegate 호출과 deadline 갱신을 먼저 완료한 뒤 terminal publish한다. |
| listener 등록 중 dispatch race | 일부 callback이 예측 불가능하게 누락/중복됨 | publish 시점 snapshot semantics를 문서화하고 add/remove 동시성 테스트를 둔다. |

## 6. API/ABI와 호환성

- 기존 `LockExtender`, `ExtendOutcome`, `ExtendDelegate`, `LeaderLeaseAutoExtender.start` 시그니처와 반환 semantics는 변경하지 않는다.
- 새 observer/event/facade 타입은 additive API다. 기존 Java caller가 사용하는 `@JvmStatic` 및 overload를 제거하거나 재배치하지 않는다.
- 기존 `LeaderElectionListener`에 extension callback을 추가하지 않는다. 서로 다른 lifecycle domain을 유지해 기존 구현체의 source compatibility를 보존한다.
- `LeaderLeaseAutoExtender.start`의 기존 JVM overload는 남겨둔다. OBS-02에서는 watchdog context 또는 registry 인자를 추가하지 않는다.
- core는 Micrometer, Spring, tracing framework에 의존하지 않는다. instrumentation wiring은 `leader-micrometer`와 `leader-spring-boot`에 한정한다.
- public event/context는 명시적 equality를 가진 일반 immutable class로 설계하고 token, raw backend payload, thread-local object를 복사·직렬화하지 않는다. `BackendError.cause`는 기존 detailed outcome의 민감한 참조를 callback 동안만 전달하며 기본 observer는 저장하지 않는다.
- 기본 동작은 observer가 전혀 등록되지 않은 경우 기존과 bytecode/latency 의미를 유지하는 zero-allocation fast path다. observer가 등록된 경우에도 callback은 bounded asynchronous dispatch로 renewal critical path를 차단하지 않는다.

## 7. 테스트와 acceptance criteria

### 7.1 core contract

- registry 등록, 제거, idempotent close, callback isolation을 검증한다.
- blocking `LockExtender`의 `Extended`, `NotHeld`, `WrongThread`, `BackendError` event와 source/execution/context를 검증한다.
- blocking/suspend Boolean wrapper와 Kotlin/Java `Duration` overload가 detailed 경로를 통해 정확히 한 event만 발행하는지 검증한다.
- suspend 경로가 blocking과 동일한 outcome vocabulary를 사용하고 cancellation을 재전파하는지 검증한다.
- fail-open과 active-scope 밖 호출이 `Extended`나 fake identity를 보고하지 않는지 검증한다.
- watchdog blocking/suspend가 동일한 delegate reference의 결과를 그대로 event로 전달하고 source만 `WATCHDOG`로 구분하는지 검증한다. backend wrapper나 delegate별 event vocabulary가 생기지 않는지 recording delegate contract test로 고정한다.
- watchdog 기존 overload에서 context가 추정되지 않고 `null`인지, 추가 context/registry overload가 없어 기존 JVM descriptor가 보존되는지 검증한다.
- observer callback의 실패와 callback 내부 `CancellationException`, bounded queue overflow/drop, add/remove와 publish 동시성, registration token ownership, no-observer fast path를 검증한다.
- observer callback의 일반 `Exception` 격리와 fatal `Error` 재전파, scheduler admission rejection과 delegate-thrown `RejectedExecutionException`의 event 경계를 각각 검증한다.
- public facade의 `javap`/Java compile fixture로 `@JvmStatic` add/remove/droppedCount 시그니처, nullable context event constructor, 명시적 `equals`/`hashCode`/redacted `toString`만 있는 일반 immutable class surface, data-class `copy`/`componentN` 부재, `hasObservers`의 `@JvmSynthetic` Java-source 비노출, 기존 `LeaderObservationOptions` constructor/`copy`/`copy$default`/`componentN` descriptor를 검증한다. `Event.toString()`과 `Context.toString()`에 exception detail, token, lock/leader identity가 없는지 회귀 테스트한다.

### 7.2 micrometer contract

- `ObservationRegistry.NOOP` 및 registry 미설정 환경에서 observer bean이 등록되지 않고 각 boundary의 `hasObservers()` guard가 event/타이밍 객체를 만들지 않는 zero-allocation fast path임을 검증한다. add/remove 동시 race는 허용된 snapshot semantics와 일치해야 한다.
- public `MicrometerObservationLeaderLeaseExtensionObserver`의 exact constructor, observation name, private/internal tag constants, Spring `AutoCloseable` registration bean의 `destroyMethod="close"`, `ObservationRegistryNotNoopCondition` 동작을 ABI/auto-configuration fixture로 검증한다.
- source/execution/outcome/result가 bounded low-cardinality로 기록되는지 검증한다.
- 기본 옵션에서 raw lock name, audit leader ID, exception details가 기록되지 않는지 검증한다.
- opt-in 옵션에서 identity/exception이 high-cardinality 또는 명시된 detail field로만 노출되는지 검증한다. 기본 observer가 `BackendError.cause`를 저장·직렬화하지 않는지도 검증한다.
- 동일한 event를 여러 observer가 처리해도 한 observer의 실패가 다른 observer와 extension 결과를 막지 않는지 검증한다.

### 7.3 문서와 train

- `docs/manual/en/core/lease-extension.md`, `docs/manual/ko/core/lease-extension.md`, root/core/micrometer/spring README에서 #529 acquire/execution observation과 #559 lease-extension observation의 관계를 설명한다.
- 현재 README의 #559 stale 문구(`deferred`, `tracked separately`, `out of scope`, `follow-up`, `별도로`, `미뤘`, `범위 밖`, `후속`)는 제거하고 실제 API·redaction·cancellation·watchdog drop/lifecycle 설명으로 교체한다. 구현 검증에서 `rg -n -i --glob 'README*' '(issue[[:space:]]*#559.*(deferred|tracked separately|out of scope|follow-up|별도로|미뤘|범위 밖|후속))|((deferred|tracked separately|out of scope|follow-up|별도로|미뤘|범위 밖|후속).*issue[[:space:]]*#559)' .`가 0건인지 모든 tracked EN/KO README를 scan한다. unrelated issue marker는 이 검사 대상이 아니다.
- 문서에는 source, outcome, redaction, cancellation, watchdog context 규칙을 포함한다.
- Spring auto-configuration contract test는 context 생성 시 context별 handle 하나와 shared registry identity별 core registration 하나만 생기는지, 동일 registry를 공유하는 parent/child·병렬 context에서 event당 callback이 한 번인지, acquire와 last-close가 교차해도 double registration/조기 close가 없는지, 마지막 context destroy 뒤 handle/core registration이 닫히고 manager가 registry/handle strong reference를 남기지 않는지 검증한다. 서로 다른 registry를 사용하는 context는 각각 한 번 등록되는지 별도로 확인한다. 동일 registry에서 옵션이 다르면 observer를 추가 등록하지 않고 `IllegalStateException`으로 fail-fast하며 redaction 약화가 없는지 검증한다. close 전에 accepted된 callback task가 실행될 수 있다는 core 정책도 함께 검증한다.
- PR body는 이슈 milestone `0.6.0`, labels `enhancement`, `feature`, `integration`, assignee `debop`을 그대로 반영한다.
- PR body의 마지막 H2는 정확히 `## DoD Status`이며, `Required checks: X/Y; N/A: N; Blocked: N` 형식의 검증 집계를 포함한다.

## 8. 범위 제외

- 새로운 backend 또는 lease renewal algorithm 추가
- backend별 lock-name/leader-id 추론 로직 추가
- 기존 `LeaderElectionListener` lifecycle event의 의미 변경
- Micrometer 외 tracing SDK에 대한 직접 의존성 추가
- 이미 병합된 OBS-01/Issue #533의 진단 API 재설계
- 이번 train에서 다이어그램 자산 추가; 시각 자료가 필요해지는 경우 별도 `bluetape-diagram` gate로 분리한다.

## 9. 대안과 거부 사유

### 대안 A — 권장: core process-local observer registry

`LockExtender`와 watchdog가 공통 event contract를 publish하고, Micrometer가 별도 observer로 구독한다. 등록/해제는 `AutoCloseable`이며 observer 오류는 격리한다.

**선택 이유:** core가 framework-neutral 상태를 유지하고, 현재 production source의 56개 watchdog call site를 backend별로 복제하지 않으며, user/watchdog parity와 redaction을 한 곳에서 검증할 수 있다.

### 대안 B — 각 elector/backend에 listener 직접 주입

각 backend가 lock name, leader ID, watchdog를 직접 감싸서 event를 만든다.

**거부 사유:** backend별 중복 logic과 누락 가능성이 커지고, 같은 `ExtendDelegate`를 쓰는 경로가 서로 다른 observation 결과를 만들 수 있다. Issue #559의 framework-neutral hook과 parity 목표에 맞지 않는다.

### 대안 C — 기존 `LeaderElectionListener`에 extension callback 추가

`onExtended` 또는 유사 callback을 기존 listener interface에 추가한다.

**거부 사유:** acquire/elected/revoked lifecycle과 lease renewal attempt를 하나의 listener domain에 결합한다. 기존 listener 구현체의 source compatibility와 event 의미를 불필요하게 흔든다.

### 대안 D — Micrometer에서 `LockExtender`를 직접 계측

core hook 없이 Micrometer가 특정 implementation과 backend를 감싼다.

**거부 사유:** framework-neutral contract가 사라지고 Spring/AOP/직접 호출/watchdog 경로 간 누락이 생긴다. 또한 core API의 fail-open/no-op semantics를 instrumentation이 독자적으로 재현하게 된다.

## 10. 구현 단계 진입 조건

다음 조건을 모두 만족해야 구현 계획과 코드 단계로 이동한다.

1. 이 사양서의 API 이름, event 필드, identity/cancellation 규칙에 대한 사용자 승인
2. 6-lane 사양 검토에서 CRITICAL/HIGH 미해결 항목이 없음
3. SPW-01..SPW-05 및 한국어 문장 자연성 KO-01..KO-06 통과
4. `git diff --check`, 미완료 표식 검색, 내부 링크·issue/PR 식별자 검증 통과
5. 승인된 사양을 기준으로 별도 implementation plan을 작성하고 다시 승인

사양 승인 전 상태는 `PENDING`이며, 구현·커밋·PR 생성·merge는 수행하지 않는다.
