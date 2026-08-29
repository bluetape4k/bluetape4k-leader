# Issue #741 Spring lease-extension observation scope 설계

## 목적

동일 JVM에서 서로 다른 `ObservationRegistry`를 사용하는 Spring application context가 함께 실행될 때, 한 context의 lease-extension event와 선택적으로 노출한 `lockName`/`auditLeaderId`가 다른 context의 telemetry에 기록되지 않도록 한다.

기존 core 사용자의 process-local global observer 계약과 공개 `LeaderLeaseExtensionEvent` ABI는 유지한다. Spring 자동 관찰은 attribution이 가능한 Spring AOP 실행 경계만 context scope에 귀속하며, attribution이 없는 직접 elector 호출은 자동 Spring registry에 전달하지 않는 fail-closed 기본값을 사용한다.

## 현재 증거

- `LeaderLeaseExtensionObservers`는 process-global registration 목록의 읽기 시점 사본을 만든 뒤 모든 observer에 같은 event를 전달한다.
- `LeaseExtensionObservationRegistrationManager`는 `ObservationRegistry` identity별 observer 수명만 관리하고 event producer ownership은 알지 못한다.
- `MicrometerObservationLeaderLeaseExtensionObserver`는 전달받은 event를 별도 필터 없이 자기 registry에 기록한다.
- `LeaderLeaseExtensionContext`에는 `lockName`과 `auditLeaderId`만 있고 Spring context owner는 없다.
- `WATCHDOG` event의 `context`는 의도적으로 `null`이며 scheduler, virtual thread 또는 coroutine에서 나중에 실행된다.
- 현재 `LeaseExtensionObservationRegistrationManagerTest`는 서로 다른 두 registry가 동일 event를 모두 받는 결함 동작을 기대한다.
- `LeaderLeaseExtensionApiContractTest`는 공개 event의 5-인자 constructor와 facade method 집합을 호환성 경계로 고정한다.

따라서 registry ref-count 변경이나 lock/leader identity 비교만으로는 안전하게 격리할 수 없다. producer 실행 시점에만 알 수 있는 opaque scope를 dispatch metadata로 전달해야 한다.

## 요구사항

1. 기존 `LeaderLeaseExtensionObservers.addObserver(observer)`는 scoped/unscoped event를 모두 받는 global wildcard 계약을 유지한다.
2. 공개 `LeaderLeaseExtensionEvent`와 `LeaderLeaseExtensionContext`에 Spring type, registry, scope token을 추가하지 않는다.
3. Spring 자동 observer는 자기 registry entry가 소유한 opaque capability와 같은 scope에서 발생한 event만 받는다.
4. 서로 다른 registry A/B에서 A event가 B에, B event가 A에 전달되는 횟수는 모두 0이어야 한다.
5. `includeLockName=true`, `includeLeaderId=true`에서도 상대 context identity가 노출되지 않아야 한다.
6. USER blocking/suspend와 WATCHDOG blocking/suspend 경로에서 scope를 잃지 않는다.
7. 같은 registry를 공유하는 parent/child context는 동일 telemetry domain으로 취급하며 event당 callback 한 번과 기존 ref-count/last-close 계약을 유지한다.
8. 서로 다른 registry의 context는 A→B와 B→A close 순서 모두에서 남은 context만 자기 event를 계속 받는다.
9. observer callback 오류, bounded admission, close 시점 callback semantics와 extension 결과는 기존과 같아야 한다.
10. Spring scope 밖의 직접 elector 호출은 자동 Spring observer에 전달하지 않으며, 명시적으로 등록한 global observer에는 계속 전달한다.

## 범위

### 포함

- core observer registration의 wildcard/scoped matching
- core execution scope carrier와 coroutine context 전파
- AOP 실행 안에서 새 thread/coroutine을 만드는 core lease adapter의 scope capture/restore
- watchdog 시작 시 scope capture
- Spring registry identity entry가 소유하는 private scope capability registration
- `LeaderElectionAspect`와 `LeaderGroupElectionAspect`의 sync/suspend/reactive 실행 scope
- 서로 다른 registry, identity opt-in, close 순서, global compatibility 회귀 테스트
- root 및 Spring README의 EN/KO 계약 갱신
- #559 manual draft EN/KO delta와 rollout/rollback/shutdown runbook 갱신

### 제외

- 공개 event constructor에 owner field 추가
- 모든 backend constructor/options에 Spring scope 주입
- Spring AOP를 거치지 않는 임의의 user-defined elector bean 자동 wrapping
- lock name, leader id, thread name 또는 application name을 이용한 추론형 filtering
- dispatcher, admission 상한, observation name/tag schema 변경
- dependency, module, workflow, BOM 변경

## 대안

### 대안 A — 채택: core opaque scope와 Spring AOP execution scope

core dispatch는 event와 별도의 opaque scope capability를 사용한다. 기존 global registration은 wildcard이고, Spring manager가 만든 registration만 같은 capability를 사용한다. Spring AOP는 elector 호출 전체에 context owner가 받은 capability를 적용하고, watchdog는 `start()` 시점의 capability를 값으로 캡처한다.

장점:

- 공개 event/observer API의 기존 의미와 ABI를 보존한다.
- WATCHDOG의 `context=null` 정책을 바꾸지 않고 정확히 분리한다.
- 다른 registry의 event가 admission permit이나 drop count를 소비하지 않게 할 수 있다.
- attribution이 없는 event를 자동 telemetry에서 제외해 identity 오염을 fail-closed로 막는다.

제약:

- Spring AOP 밖의 직접 elector 호출은 자동 registry에 귀속되지 않는다.
- 모든 AOP 실행 모델에서 scope 적용 지점을 빠짐없이 검증해야 한다.

### 대안 B — 기각: 모든 backend/options에 producer scope 저장

Spring이 만든 모든 elector option, handle, watchdog에 context token을 명시적으로 저장하면 직접 bean 호출까지 귀속할 수 있다. 그러나 backend 전반의 constructor/call site와 serialization/equality 경계가 바뀌고, public compatibility 위험과 검증 행렬이 크게 증가한다. Issue #741의 승인 범위보다 넓으므로 기각한다.

### 대안 C — 기각: global broadcast 유지 또는 두 번째 registry 거부

identity option을 문서로 경고하거나 multi-registry에서 두 번째 자동 등록을 거부할 수 있다. 전자는 보안 위험을 남기고, 후자는 정상 multi-context telemetry를 소실한다. 양쪽 context가 독립 telemetry를 유지해야 하는 완료 조건을 만족하지 못한다.

## 상세 설계

### 1. Core scope carrier

core에 Spring type을 모르는 실행 scope carrier를 둔다.

- capability는 scoped registration 생성 시 core가 만들며 외부 caller가 token 값을 주입하는 API를 제공하지 않는다.
- capability class의 constructor는 외부 호출에 닫고 equality를 재정의하지 않아 referential identity로 비교한다.
- blocking 경계는 `ThreadLocal` save/set/restore를 사용한다.
- coroutine 경계는 capability마다 한 번 만든 같은 `ThreadLocal`의 `ThreadContextElement`를 재사용한다.
- scope token은 event, log, `toString()`, exception, metric tag에 포함하지 않는다.
- cross-module bridge는 registration handle의 instance operation으로만 제공하고 Java source 호출은 `@JvmSynthetic`로 막는다. ambient capability를 읽는 accessor는 core `internal`로 유지하며 binary API test로 public `current()` descriptor가 없음을 고정한다.

중첩 scope는 이전 값을 복원한다. scope가 없으면 `null`이며 scoped Spring observer와 일치하지 않는다.
외부 Kotlin caller가 자기 scoped observer/capability를 만들 수 있어도 ambient capability를 읽는 public API가 없고 다른 registration의 capability를 얻거나 지정할 수 없으므로 다른 Spring context를 impersonate할 수 없다. Spring context owner bean과 manager entry는 `internal`로 유지한다.

정확한 cross-module bridge shape은 다음과 같다.

- `LeaderLeaseExtensionObservationScope`: public opaque final class, private constructor, `AutoCloseable` 구현
- `LeaderLeaseExtensionObservers.addScopedObserver(observer): LeaderLeaseExtensionObservationScope`: public `@JvmSynthetic`; caller-supplied scope parameter 없음
- `LeaderLeaseExtensionObservationScope.withScope(block)`와 `asContextElement()`: public `@JvmSynthetic` instance operation; caller가 등록 시 받은 자기 capability만 설치 가능
- ambient scope lookup은 core module의 `internal currentOrNull()`만 제공하며 public Kotlin/JVM ABI에는 `current()` accessor가 없다.
- 기존 `LeaderLeaseExtensionObservers.hasObservers()`와 `publish(event)` descriptor는 그대로 보존하며 unscoped/wildcard path를 의미한다.
- 새 `hasObservers(scope)`와 `publish(event, scope)` overload는 public `@JvmSynthetic` additive bridge이고 core watchdog/adapter가 캡처한 capability만 받는다.

따라서 Kotlin `internal`을 module 간 계약으로 사용하지 않는다. `LeaderLeaseExtensionApiContractTest`는 기존 bridge와 새 overload/factory의 exact public-synthetic signature, scope private constructor, event 5-인자 constructor를 함께 고정한다.

Public Kotlin caller가 `addScopedObserver`로 받는 handle은 그 caller가 등록한 observer만 위한 scope다. 이 handle은 Spring `ObservationRegistry`와 연결되지 않고 Spring manager의 canonical capability를 읽거나 대체하지 못한다. caller는 `use`/`close`로 수명을 끝내야 하며 close 뒤 재설치해도 scoped callback은 발생하지 않는다. Java source에서는 `@JvmSynthetic` bridge가 보이지 않으므로 explicit global `addObserver`만 기존 public Java surface로 유지된다.

### 2. Registration matching

registration은 observer와 선택적 capability를 함께 가진다.

- 기존 `addObserver(observer)`는 scope가 없는 wildcard registration을 만든다.
- Spring용 scoped registration factory는 non-null capability와 registration handle을 함께 반환하며 caller-supplied token을 받지 않는다.
- wildcard registrations와 capability별 registrations는 별도 copy-on-write bucket에 저장한다. capability bucket index는 capability의 identity semantics를 사용한다.
- `hasObservers(scope)`는 wildcard bucket 또는 해당 capability bucket의 비어 있지 않은 상태만 O(1), 무할당으로 확인한다.
- `publish`는 wildcard와 해당 capability bucket의 읽기 시점 항목만 순회하고 다른 scope registration은 복사하거나 검사하지 않는다.
- matching되지 않은 registration에는 global/per-observer admission permit을 요청하지 않고 drop count도 증가시키지 않는다.
- scope close는 한 번만 `active=false`로 선형화한 뒤 registration을 닫고 identity bucket을 map에서 제거한다. 반복 close는 no-op이고 accepted task의 late callback 정책은 유지한다. close/publish race와 weak-reference collection test로 dispatcher가 닫힌 capability를 계속 보유하지 않음을 검증한다.

global admission이 포화된 경우에도 남은 wildcard와 같은 capability registration만 drop으로 기록한다. 다른 capability traffic은 permit이나 drop delta를 직접 소비하지 않는다. process-global wildcard observer callback은 모든 event를 받는 기존 계약이므로 global cap을 공유하며 scoped delivery의 cap을 간접 소비할 수 있다.

### 3. USER event

`LockExtender`는 publish 직전에 현재 execution scope를 읽는다.

- Spring AOP action 안에서는 context owner의 capability scope가 존재한다.
- blocking과 fail-open sync path는 `ThreadLocal` scope를 사용한다.
- suspend/Mono/Flux/Flow path는 coroutine context element로 dispatcher 전환 후에도 같은 scope를 복원한다.
- active handle이 없거나 이름이 일치하지 않는 USER event도 현재 Spring execution scope가 있으면 해당 registry에만 전달한다.
- Spring scope 밖이면 global observer만 받는다.
- `hasObservers(currentScope) == false`이면 timer/context/event를 만들기 전에 observation 분기만 종료하고 실제 lease extension 결과/예외 흐름은 그대로 유지한다.

`LeaderElectorLeaseAdapter`와 `SuspendLeaderElectorLeaseAdapter`가 AOP 실행 안에서 새 virtual thread 또는 coroutine을 만들 때는 현재 capability를 생성 시점에 캡처하고 그 실행 경계에서 복원한다. scope 밖에서 직접 호출된 adapter는 계속 unscoped다.

### 4. WATCHDOG event

`LeaderLeaseAutoExtender.start()`는 호출 시점의 capability를 local immutable value로 캡처한다. 각 tick은 current thread의 ambient 값에 의존하지 않고 캡처한 capability를 `hasObservers`와 `publish`에 직접 전달한다.

WATCHDOG도 matching observer가 없으면 timer/context/event 생성 전에 observation 분기를 종료한다.

이 방식은 다음 전환에 동일하게 적용한다.

- shared scheduled executor
- optional virtual-thread blocking extension
- suspend watchdog의 `CoroutineScope(Dispatchers.Default)`

watchdog tick 종료, rejection, backend error, cancellation 의미는 바꾸지 않는다. close와 경쟁해 이미 accept된 callback은 기존 close/in-flight 계약대로 끝날 수 있지만 capability revoke 뒤에는 새 scoped callback을 accept하지 않는다. 실행 중 USER sync/suspend/Mono/Flux/Flow action이 revoke된 capability로 event를 만들면 automatic observer에는 0회이고 explicit global observer에는 기존 wildcard 계약대로 전달된다. `withScope`/`ThreadContextElement` 종료 시에는 revoke 여부와 무관하게 이전 nested scope를 복원한다. 같은 registry가 나중에 다시 등록되면 새 manager entry와 새 capability를 사용하므로 이전 action/watchdog event는 새 context에 귀속되지 않는다.

### 5. Spring scope ownership

Spring automatic observation의 telemetry domain은 선택된 `ObservationRegistry` object identity로 결정하지만, dispatch token은 registry object 자체가 아니라 manager entry가 소유한 opaque capability다.

- 같은 registry를 공유하는 parent/child context는 같은 scope와 telemetry domain을 공유한다.
- 서로 다른 registry는 서로 다른 scope가 된다.
- manager는 기존 registry identity map, option conflict fail-fast, ref-count, last-close를 유지하며 entry마다 하나의 capability를 소유한다.
- 새 core registration만 scoped registration으로 바꾼다.
- coordinator의 late registration과 NOOP registry 판정 순서를 유지한다.

각 application context는 하나의 internal scope owner bean을 가지며 aspect는 생성 시 그 owner identity에 고정된다. coordinator는 registry post-processing 뒤 manager에서 얻은 canonical capability를 owner에 정확히 한 번 활성화한다. 같은 registry를 공유하는 parent/child owner는 같은 manager-entry capability를 받고, 서로 다른 registry owner는 다른 capability를 받는다. context close는 자기 handle만 release하며 last-close가 registration과 capability를 revoke한다.

`LeaderObservationAutoConfiguration`은 `@ConditionalOnMissingBean(search = SearchStrategy.CURRENT)`로 각 context에 선택된 registry identity를 보유하는 고정 이름의 internal owner bean을 먼저 만든다. 기존 public 3-인자 coordinator factory descriptor는 그대로 두고 factory body/coordinator가 같은 local `BeanFactory`의 owner를 조회한다. coordinator가 `afterSingletonsInstantiated()`에서 manager registration을 획득하면 그 owner를 canonical capability로 한 번 활성화한다. `LeaderBeanSelector` bean 자체와 aspect bean도 `SearchStrategy.CURRENT`로 context-local 생성하고, selector는 `HierarchicalBeanFactory.containsLocalBean`으로 자기 `BeanFactory`의 고정 이름 owner만 조회하며 parent의 다른 owner를 후보 선택하지 않는다.

`LeaderAopAutoConfiguration`은 기존 factory parameter/descriptor를 바꾸지 않고, aspect 생성 직후 selector가 반환한 fixed owner를 internal property로 연결한다. 수동 생성 aspect와 owner bean이 없는 경우는 no-op scope다. owner는 활성화 전/close 후 `null`을 반환해 fail-closed하고 다른 registry를 동적으로 resolve하지 않는다. 기존 5/6-인자 factory와 aspect constructor descriptor는 모두 유지한다.

registry 선택은 Spring의 기존 single-candidate/`@Primary` 규칙을 따른다. 같은 registry parent/child는 하나의 manager entry와 capability를 공유하고, distinct registry는 context마다 자기 owner에 고정된다. 동일 registry가 다른 options로 두 번째 등록되면 startup을 fail-fast하며 예외는 원인과 복구 방법(옵션 통일 또는 해당 context tracing 비활성화)을 포함하되 registry object/string identity를 노출하지 않는다.

### 6. AOP 적용 경계

`LeaderElectionAspect`와 `LeaderGroupElectionAspect`는 backend elector를 호출하고 action이 끝나는 전체 구간에 scope를 적용한다.

- sync: owner가 제공한 `LeaderLeaseExtensionObservationScope.withScope { ... }`
- suspend/reactive coroutine bridge: manager entry에서 재사용하는 `ThreadContextElement`로 `withContext`를 적용한다.
- reentrant와 fail-open action도 같은 scope 안에 둔다.
- stream은 subscription/collection 시점의 실제 elector 실행을 scope로 감싼다.

Mono/Flux의 보장은 aspect가 소유한 coroutine bridge와 그 안의 suspend continuation에 한정한다. Reactor `map`/`filter`처럼 coroutine continuation 밖에서 실행되는 임의 operator callback에 `ThreadLocal`을 전파한다고 주장하지 않는다. 그런 callback에서 직접 `LockExtender`를 호출하면 fail-closed unscoped event가 되며 automatic Spring observer에는 전달하지 않는다. signal마다 scope/context element를 새로 만들거나 설치하지 않는다.

metadata resolution bypass에는 scope를 적용하지 않는다. 해당 호출은 leader execution이 아니므로 lease-extension automatic ownership을 주장하지 않는다.

지원 행렬은 다음과 같다.

| Aspect | sync | suspend | Mono | Flux | Flow |
|---|---:|---:|---:|---:|---:|
| `LeaderElectionAspect` | 지원 | 지원 | 지원 | 지원 | 지원 |
| `LeaderGroupElectionAspect` | 지원 | 지원 | 지원 | 미지원/기존 검증 오류 | 미지원/기존 검증 오류 |

group `Flux`/`Flow`는 scope 설치 전에 기존 validation에서 거부되며 telemetry event를 만들지 않는다.

## 실패 모드와 대응

| 실패 모드 | 영향 | 대응 |
|---|---|---|
| scope를 event public field에 추가 | 5-인자 constructor ABI 파손 및 token 노출 | event 외부 dispatch metadata로 제한하고 API contract test를 유지한다. |
| caller-supplied registry/token으로 scope 설치 | 다른 context scope impersonation | core가 registration과 capability를 함께 만들고 arbitrary token 입력 API를 제공하지 않는다. |
| watchdog가 ambient ThreadLocal을 tick 시점에 조회 | scheduler thread의 다른/없는 scope로 오귀속 | `start()`에서 immutable scope를 캡처하고 tick publish에 명시적으로 전달한다. |
| matching 전에 admission permit 획득 | 다른 context traffic이 registry quota/drop count를 오염 | scope matching 후 permit을 획득한다. |
| suspend/reactive에서 scope context element 누락 | dispatcher 전환 후 event가 unscoped 처리 | blocking/suspend/Mono/Flux/Flow 경계별 회귀 테스트를 둔다. |
| same-registry context마다 core registration 추가 | event 중복 기록 | 기존 registry identity manager의 단일 entry/ref-count를 유지한다. |
| close와 accepted callback 경쟁 | 닫힌 context resource에 late callback | 기존 close 시점 semantics를 문서화하고 새 event acceptance만 차단한다. 이미 accepted된 callback은 기존 계약대로 완료될 수 있다. |
| 직접 elector 호출을 잘못된 context에 추론 귀속 | identity 교차 노출 | automatic registry에는 전달하지 않는 fail-closed 정책을 문서화하고 global explicit observer만 유지한다. |
| close 후 stale watchdog가 재등록 context에 전달 | 종료된 context identity의 새 registry 오염 | last-close로 capability를 revoke하고 재등록 entry는 새 capability를 사용한다. |

## 호환성

- `LeaderLeaseExtensionEvent` 5-인자 constructor를 유지한다.
- `LeaderLeaseExtensionObservers`의 기존 non-synthetic public method set을 유지한다.
- `addObserver`는 모든 process-local event를 받는다.
- Micrometer observation name, low/high-cardinality key, error detail 정책을 유지한다.
- same-registry option conflict와 close/ref-count 의미를 유지한다.
- 새 internal/synthetic scope bridge는 Spring integration을 위한 additive surface다.
- binary compatibility와 public facade reflection test를 실행한다.

`includeExceptionDetails=true`의 기존 `Throwable` 전달 정책은 이번 변경이 추가하거나 확장하는 데이터 경계가 아니므로 유지한다. 이 opt-in은 raw exception message와 causal chain이 exporter에 전달될 수 있음을 의미하며 library가 SQL, credential, token을 redact한다고 가정하지 않는다. 운영자는 secret-bearing exception을 만들지 않거나 exporter-side redaction을 적용하고, 보장할 수 없으면 `includeExceptionDetails=false`를 유지한다. library-level exception redaction은 별도 security hardening 범위로 defer한다. 이번 PR은 A의 raw error가 B registry로 교차 전달되지 않음과 scope capability 자체가 error/tag에 포함되지 않음을 검증한다.

### 사용자 마이그레이션

- 기존 Spring AOP annotation 경로는 설정 변경 없이 registry별 automatic telemetry를 받는다.
- AOP 밖에서 elector/adapter를 직접 호출하며 automatic lease-extension telemetry에 의존한 사용자는 해당 실행을 `@LeaderElection`/`@LeaderGroupElection` 경계로 옮기거나 명시적 process-global `LeaderLeaseExtensionObservers.addObserver`를 등록한다.
- explicit global observer는 모든 context event를 받으므로 privacy/tenant 격리가 필요한 registry exporter 대체재가 아니다.
- 같은 Micrometer observer를 manual global과 Spring automatic에 동시에 등록하면 중복 기록되므로 둘 중 하나만 사용한다. 진단 시 동일 event의 observation count가 2배인지 확인한다.
- EN/KO README는 변경 전 global broadcast와 변경 후 scoped automatic/fail-closed direct-call behavior를 before/after 예제로 설명한다.

### Rollout, rollback, shutdown

- canary는 서로 다른 registry A/B를 동시에 실행해 각 registry에서 자기 lock/leader identity만 1회, 상대 identity 0회인지 확인하고 `droppedCount()` delta도 기록한다.
- cross-context event, startup option conflict, unexpected duplicate observation이 발견되면 해당 `ApplicationContext`의 startup configuration에 `bluetape4k.leader.observability.tracing.enabled=false`를 설정하고 context/process를 재시작해 automatic lease-extension observation을 비활성화한다. runtime refresh switch가 아니며 이미 시작된 context에는 소급 적용되지 않는다. 재시작 뒤 local owner bean 부재, automatic 0건, explicit global observer 1건을 확인한다. 이 switch는 explicit global observer를 제거하지 않으므로 별도 close가 필요하다.
- binary rollback은 이전 artifact로 되돌린 뒤 multi-registry automatic observer가 다시 global broadcast 의미가 됨을 운영자가 인지하고, 격리 요구가 있으면 tracing을 disabled 상태로 유지한다.
- graceful shutdown은 AOP traffic 중단, context registration close, registry/exporter의 자체 grace period, exporter 종료 순서로 수행한다. library registration close는 내부 drain 대기 없이 유한 시간에 반환하고 close 이후 새 scoped admission만 차단한다. 이미 accepted된 callback의 exporter 전달은 보장하지 않으며 운영자는 exporter의 기존 shutdown timeout으로만 수용한다. shutdown smoke는 blocked callback 중 close가 즉시 반환하는지, 새 automatic callback이 0건인지, exporter가 살아 있는 동안 해제한 accepted callback이 기존 in-flight 계약대로 끝날 수 있는지 구분해 기록한다.
- scope-excluded direct call은 drop이 아니므로 `droppedCount()`를 증가시키지 않는다. 장애 구분은 bounded explicit global observer로 동일 event 존재를 확인한 뒤 automatic registry 0건이면 intentional scope exclusion, global observer도 0건이면 producer/no-observer 경로, `droppedCount()` 증가면 admission saturation으로 판정한다.

## 테스트 전략

### Core

1. global observer가 scoped A, scoped B, unscoped event를 모두 받는다.
2. scoped observer A/B는 자기 scope만 받으며 unscoped event를 받지 않는다.
3. scope mismatch event가 admission/drop count를 소비하지 않는다.
4. nested blocking scope가 이전 값을 복원한다.
5. coroutine dispatcher 전환 후 scope가 유지되고 종료 후 제거된다.
6. USER blocking/suspend event가 올바른 scope로 한 번 전달된다.
7. WATCHDOG blocking/suspend/virtual-thread event가 시작 시 scope로 전달된다.
8. callback failure, close 시점, bounded admission 기존 테스트가 계속 통과한다.
9. 공개 event/facade API contract가 변하지 않는다.
10. 1024 global in-flight 포화와 A/B 동시 traffic에서도 mismatched scope의 permit/drop delta는 0이고 matched/wildcard delivery만 drop으로 집계된다.
11. lease adapter가 만든 virtual thread/IO coroutine은 AOP scope를 보존하고 direct adapter 호출은 unscoped다.
12. close/reopen 시 이전 capability watchdog event가 새 scoped registration에 전달되지 않는다.
13. public JVM/Kotlin ABI에는 ambient `current()` accessor가 없고, caller-owned scope를 보유·재사용해도 다른 Spring automatic registration을 impersonate하지 못한다.
14. caller-owned scope의 Korean KDoc과 EN/KO migration matrix가 자기 observer 전용, Spring registry 비연결, close 수명, Java `@JvmSynthetic` 제약을 설명한다.

### Spring

1. 서로 다른 registry A/B와 `includeLockName=true`, `includeLeaderId=true`에서 A identity가 A에만, B identity가 B에만 기록된다.
2. A→B와 B→A close 순서 후 남은 context만 자기 event를 받는다.
3. 같은 registry parent/child는 event당 callback 한 번과 ref-count를 유지한다.
4. conflicting options는 두 번째 scoped registration 없이 fail-fast한다.
5. NOOP registry와 tracing disabled에서는 registration이 없다.
6. sync/suspend/Mono/Flux/Flow 및 fail-open/reentrant 실행에서 scope를 잃지 않는다.
7. scope 밖 직접 `LockExtender` event는 automatic registry에는 0회, explicit global observer에는 1회 전달된다.
8. Reactor non-suspend operator callback은 자동 scope 보장 범위 밖이며 direct extension이 automatic registry에 0회임을 고정한다.
9. distinct parent/child registry의 동시 실행은 각 context-fixed owner에만 기록된다.
10. sync/suspend/Mono/Flux/Flow/group의 exception, cancellation, nested scope, thread reuse 후 scope가 제거된다.
11. A/B 양방향 close race와 watchdog first-tick/in-flight/shutdown/restart 경계에서 새 admission과 재등록 오귀속이 없다.
12. option conflict 예외가 registry identity를 노출하지 않고 옵션 통일/비활성화 복구를 안내한다.
13. `includeExceptionDetails=true`의 secret-like exception은 source registry에만 전달되고 상대 registry에는 0건이며 scope capability는 error/tag/log에 노출되지 않는다. raw payload redaction 책임은 exporter/operator에 있음을 문서화한다.
14. active sync/suspend/Mono/Flux/Flow action과 context close가 경쟁하면 revoke 뒤 automatic callback은 0회, explicit global callback은 1회이며 nested scope 복원과 reopen-new-capability 격리가 유지된다.
15. 기본값과 `includeExceptionDetails=false`에서는 raw throwable/error payload를 만들거나 exporter에 전달하지 않는다.

### 성능

1. `hasObservers(scope)`는 O(1), 무할당이고 `publish`는 wildcard와 matching capability bucket만 순회한다.
2. capability별 `ThreadContextElement`는 manager entry 수명 동안 재사용하며 event/signal당 만들지 않는다.
3. 기존 Spring advice JMH에 no-observer, scoped-match, scoped-mismatch, global과 sync/suspend/Mono/Flux/Flow/watchdog case를 추가한다.
4. 동일 환경의 변경 전/후 3-fork 비교에서 `gc.alloc.rate.norm`이 새 per-event allocation을 보이지 않고 throughput/average-time median 회귀가 15% 이내인지 기록한다. 이 수치는 비결정적 CI fail gate가 아니라 구현 검토 evidence다.

### 검증 명령

```bash
./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtension*' --rerun-tasks
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaseExtensionObservation*' --rerun-tasks
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderElectionAspect*' --rerun-tasks
./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-spring-boot:test
./gradlew detekt
./gradlew checkBinaryCompatibility
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
git diff --check
```

## 문서

- root `README.md`와 `README.ko.md`: global explicit observer와 Spring scoped automatic observer 경계를 구분한다.
- `leader-spring-boot/README.md`와 `README.ko.md`: registry identity ownership, same-registry 공유, direct elector fail-closed, close semantics를 설명한다.
- Spring EN/KO README에 `LeaderElectionAspect`/`LeaderGroupElectionAspect` 실행 모델 지원 행렬과 direct-call migration 예제를 추가한다.
- `docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.{en,ko}.md`에 #741 delta, registry ownership, rollout/rollback/shutdown 절차를 추가한다. versioned manual의 `releaseRef`/`releaseCommit`은 변경하지 않고 미출시 주장은 draft에만 둔다.
- 기존 #559의 process-local global facade 결정은 폐기하지 않고 Spring automatic registration에만 scoped dispatch를 추가한 것으로 기록한다.

## 수용 기준

- [ ] public global observer와 event ABI가 유지된다.
- [ ] 서로 다른 Spring registry 사이 USER/WATCHDOG cross-delivery가 0이다.
- [ ] identity opt-in에서도 상대 lock/leader identity가 0건이다.
- [ ] same-registry parent/child callback/ref-count 계약이 유지된다.
- [ ] 서로 다른 registry의 양쪽 close 순서가 안전하다.
- [ ] sync/suspend/reactive scope 전파와 watchdog capture가 검증된다.
- [ ] adapter thread/coroutine 전파와 Reactor non-suspend operator 제외 경계가 검증된다.
- [ ] scope 밖 직접 호출은 automatic telemetry에서 제외되고 global observer에는 유지된다.
- [ ] indexed dispatch, mismatch admission/drop, allocation/latency evidence가 검증된다.
- [ ] rollout/rollback/shutdown 및 manual draft provenance가 EN/KO로 검증된다.
- [ ] raw exception opt-in의 운영 전제와 cross-registry 0건이 검증된다.
- [ ] core/Spring targeted 및 module test, detekt, ABI, diff check가 통과한다.
- [ ] EN/KO 문서가 동일한 안전 경계를 설명한다.

## 승인 및 writer gate

- 사용자 승인: 2026-08-29, 대안 A와 direct-elector fail-closed 경계를 명시적으로 승인함.
- SPW-01: PASS — 독자는 Issue #741 구현/검토자이며 코드 식별자를 보존한 한국어 기술 문서로 작성했다.
- SPW-02: PASS — 문제, 대안, 선택, 경계, 실패 모드, 검증과 DoD를 분리했다.
- SPW-03: PASS — 한국어 문장 흐름과 EN/KO artifact 역할을 점검했다.
- SPW-04: PASS — 모든 핵심 주장을 현재 source/test/issue 증거에 연결했다.
- SPW-05: PASS — scope, registry identity, global compatibility, direct-call 제외가 서로 모순되지 않도록 최종 read-back했다.
