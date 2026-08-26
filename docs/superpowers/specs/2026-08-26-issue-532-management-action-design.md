# Issue #532 알려진 lock용 opt-in leader management action 설계

## 1. 상태와 범위

- Issue: [#532](https://github.com/bluetape4k/bluetape4k-leader/issues/532)
- Epic: [#699](https://github.com/bluetape4k/bluetape4k-leader/issues/699)
- Train: OBS-04
- 선행: #533 diagnostics SPI, #559 lease-extension observation
- 기준: `origin/develop` `783f38ba306a44d35118414b9838d55618caae2a`
- 승인된 선택: **Core가 소유 핸들 registry를 제공하고 framework adapter가 opt-in write
  surface를 연결한다.**

이 문서는 #532 구현 전에 고정하는 설계 명세다. 목표는 운영자가 이미 이 JVM이
소유한 **알려진 lock**을 안전하게 해제할 수 있는 단일 action을 Spring Actuator와
Ktor management surface에 추가하는 것이다. 상태 조회와 diagnostics는 계속 읽기
전용이며, write surface는 기본적으로 존재하지 않는다.

이번 issue의 종료 조건은 다음과 같다.

1. `leader-core`에 blocking/suspend lease handle을 소유 증명과 함께 등록하고
   해제하는 framework-neutral contract가 있다.
2. 등록된 handle이 정확히 하나이고 release 직전 `HELD`로 확인된 경우에만
   conditional release를 수행한다.
3. 미등록, 중복, 이미 해제됨, ownership 불명, 잘못된 이름은 명시적인
   no-mutation outcome을 반환한다. release 이후 확인 실패는 별도의
   `RELEASE_UNCONFIRMED`로 표시하고 성공으로 가장하지 않는다. force-steal,
   backend-wide delete, 임의 metadata 삭제는 구현하지 않는다.
4. Spring과 Ktor write surface는 각각 명시적인 opt-in과 애플리케이션 소유의
   인증/네트워크 경계를 갖는다.
5. 기본 비활성, 소유권 경합, 동시 등록/해제, release 예외와 route exposure를
   deterministic test로 고정하고 한국어/영어 README에 운영 경계를 문서화한다.

## 2. 현재 근거와 외부 자료

### 2.1 repository 근거

- `leader-core`의 `LeaderLeaseHandle`은 `lockName`, `auditLeaderId`,
  `acquiredAt`, `ownershipStatus()`, `isStillHeld()`, idempotent `release()`를
  제공한다. backend token은 public contract에 노출되지 않는다.
- `LeaderLeaseAcquirer`와 `SuspendLeaderLeaseAcquirer`는 request-scoped lease를
  반환하고, 정상 contention을 `null`로 표현한다. 이 capability는 `runIfLeader`
  action의 내부 lock handle과 별개다.
- `leader-spring-boot`의 `LeaderElectionStatusEndpoint`는
  `@Endpoint(id = "leaderElection")`와 `@ReadOperation`으로 상태만 노출한다.
  `LeaderElectionActuatorAutoConfiguration`은
  `management.endpoint.leaderElection.enabled=true`일 때만 endpoint를 등록한다.
- `leader-ktor`의 `LeaderElectionManagementRoute`와
  `LeaderElectionPluginConfig.managementRouteEnabled`는 기본 비활성 GET status
  route와 정렬된 lock-name registry를 제공한다.
- 현재 `runIfLeader` 구현은 action의 `finally`에서 내부
  `LeaderLockHandle`을 해제하고 global active-handle registry를 공개하지 않는다.
  따라서 이번 action은 공개된 `LeaderLeaseHandle`만 대상으로 하며, 일반
  `runIfLeader` lock을 이름만으로 강제 해제하지 않는다.
- 기존 `LeaderRouteLeaseRuntime`은 request lease handle의 shutdown lifecycle을
  소유한다. 구현 시 runtime이 active handle의 registry registration을 만들고
  terminal cleanup에서 registration만 닫는다. registry가 lease lifecycle을
  대신 소유하거나 shutdown 때 임의 release하지 않는다.

### 2.2 공식 자료

- Spring Boot custom endpoint는 `@Endpoint`와 operation annotation으로
  노출되며, write operation은 명시적인 `@WriteOperation`으로 선언한다.
  <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>
  <https://docs.spring.io/spring-boot/api/java/org/springframework/boot/actuate/endpoint/annotation/WriteOperation.html>
- Ktor 인증은 애플리케이션이 `authenticate` route scope와 provider를 구성하는
  방식이다. Ktor integration은 인증 provider나 allow-list를 대신 설치하지 않는다.
  <https://ktor.io/docs/server-auth.html>
  <https://api.ktor.io/3.0.x/ktor-server-auth/io.ktor.server.auth/authenticate.html>

공식 자료는 endpoint/route 설치 경계만 확인하는 참고자료다. lease ownership과
conditional release의 최종 권위는 이 repository의 core contract와 backend
구현이다.

## 3. 문제와 비목표

### 문제

상태 endpoint는 현재 JVM이 어떤 lock을 관찰하는지만 보여 준다. 운영 중 장애나
drain 상황에서 이미 이 JVM이 보유한 request lease를 해제할 수 있는 공통 action이
없다. 이름만 받아 backend delete를 실행하면 다른 JVM의 lease를 훔치거나 fencing
token을 우회할 수 있다.

### 비목표

- `LeaderElector`, `AsyncLeaderElector`, `VirtualThreadLeaderElector`,
  `SuspendLeaderElector`의 기존 method signature 변경
- 일반 `runIfLeader` 내부 handle을 전역으로 추적하거나 lock 이름만으로 해제
- Redis/DB/etcd 등 backend별 delete API, force-steal, fencing token 입력, 전체
  namespace 삭제
- stale status cache 삭제, metadata 강제 갱신, force refresh
- group/semaphore leader의 slot 전체 해제
- core에 Spring, Ktor, Actuator, Ktor auth, serialization dependency 추가
- 기본 상태 GET route/endpoint의 response 또는 exposure semantics 변경

## 4. 선택한 설계

### 4.1 Core 공통 결과와 action

`leader-core`에 다음 additive API를 추가한다.

```kotlin
enum class LeaderManagementAction {
    RELEASE,
}

enum class LeaderManagementActionOutcome {
    RELEASED,
    INVALID_LOCK_NAME,
    NOT_REGISTERED,
    AMBIGUOUS,
    NOT_HELD,
    OWNERSHIP_UNKNOWN,
    RELEASE_UNCONFIRMED,
    RELEASE_FAILED,
    AUTHORIZATION_REQUIRED,
    AUTHORIZATION_FAILED,
}

data class LeaderManagementActionResult(
    val action: LeaderManagementAction,
    val outcome: LeaderManagementActionOutcome,
    val mutationAttempted: Boolean,
)
```

`LeaderManagementActionResult`에는 token, backend exception, raw leader identity,
thread 정보가 포함되지 않는다. `mutationAttempted`는 release 호출이 실제로
시작됐는지만 나타내며, `RELEASED`가 아니면 성공을 주장하지 않는다. 모든
no-mutation outcome의 `mutationAttempted`는 `false`다. `RELEASE_UNCONFIRMED`와
`RELEASE_FAILED`는 release를 시도했으므로 `true`일 수 있지만, 후속 운영자가
재시도할 수 있다는 보장은 하지 않는다.

lock 이름 입력은 public registry의 `register` 경계에서
`io.bluetape4k.support.requireNotBlank`로 검증한다. management route가 전달한
빈/공백 selector는 registry에 도달하기 전에 `INVALID_LOCK_NAME`으로 변환하며
backend를 호출하지 않는다. 이름의 canonicalization이나 wildcard expansion은
하지 않는다.

### 4.2 Blocking registry

`io.bluetape4k.leader.LeaderManagementActionRegistry`는 process-local registry다.

```kotlin
class LeaderManagementActionRegistry : AutoCloseable {
    fun register(handle: LeaderLeaseHandle): AutoCloseable
    fun release(lockName: String): LeaderManagementActionResult
    fun registeredLockNames(): List<String>
    override fun close()
}
```

계약은 다음과 같다.

- `register`는 handle object identity를 기준으로 한 번만 등록한다. 같은 handle을
  반복 등록해도 registration token은 idempotent하게 동작하며, 서로 다른 handle이
  같은 `lockName`을 가지면 별도 entry로 보관한다.
- 등록 token의 `close()`는 registry entry만 제거하고 lease를 release하지 않는다.
  lease lifecycle은 handle 소유자가 계속 책임진다. registry `close()`도 모든
  registration을 닫아 action admission을 멈출 뿐 backend lock을 임의로 해제하지
  않는다.
- lock별 동시 action은 한 번만 진행한다. 다른 action이 이미 같은 lock의
  registration을 reserve한 동안에는 `AMBIGUOUS` 또는
  `RELEASE_UNCONFIRMED`를 반환하고 두 번째 backend 호출을 하지 않는다.
- `release(lockName)`는 먼저 정확히 하나의 active registration을 선형화한다.
  등록 0개는 `NOT_REGISTERED`, 2개 이상은 `AMBIGUOUS`다.
- 유일한 handle의 `ownershipStatus()`가 `HELD`가 아니면 backend release를
  호출하지 않는다. `NOT_HELD`와 `UNKNOWN`을 구분하여 각각 결과를 반환한다.
- `HELD`를 확인한 뒤 handle의 idempotent `release()`를 최대 한 번 호출하고,
  다시 `ownershipStatus()`를 확인한다. 후속 상태가 `NOT_HELD`면 `RELEASED`,
  `HELD` 또는 `UNKNOWN`이면 `RELEASE_UNCONFIRMED`다.
- `release()`가 일반 예외를 던져도 exception detail을 외부로 보내지 않고
  `RELEASE_FAILED`를 반환한다. `Error`는 core 경계에서 삼키지 않는다.
- registration 종료와 action이 교차해도 이미 reserve한 동일 handle만 처리한다.
  새로 등록된 handle로 자동 전환하거나 이름만으로 다른 lease를 해제하지 않는다.
- `registeredLockNames()`는 정렬된 이름만 반환하며 상태 endpoint의 lock-name
  source와 혼용하지 않는다. backend state를 다시 조회하는 메서드가 아니다.

### 4.3 Suspend registry

blocking release를 `runBlocking`으로 감싸지 않기 위해
`io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry`를 같은
outcome contract로 추가한다.

```kotlin
class SuspendLeaderManagementActionRegistry : AutoCloseable {
    fun register(handle: SuspendLeaderLeaseHandle): AutoCloseable
    suspend fun release(lockName: String): LeaderManagementActionResult
    fun registeredLockNames(): List<String>
    override fun close()
}
```

두 registry는 결과 enum, lock별 exactly-one 규칙, ownership pre-check/post-check,
registration token semantics를 공유한다. suspend registry의 `release`와 cleanup은
structured concurrency를 지키며 caller cancellation에서 ownership을 성공으로
보고하지 않는다. registry 내부에서 blocking scheduler나 `runBlocking`을 사용하지
않는다.

### 4.4 Handle registration 수명

`LeaderRouteLeaseRuntime`과 향후 Ktor lease integration은 다음 handoff를 사용한다.

```text
lease acquired
    -> registry.register(handle)
    -> request/route owns handle + registration token
    -> management action may reserve and release the same handle
    -> normal completion releases handle
    -> terminal callback closes registration token exactly once
```

action이 handle을 release한 경우에도 정상 completion이 다시 `handle.release()`를
호출할 수 있다. handle의 기존 idempotent contract가 backend 호출을 at-most-once로
보장한다. registration token을 먼저 닫아 action을 숨긴 뒤 handle을 release하는
순서는 사용하지 않는다. shutdown은 신규 등록을 막고 기존 request cleanup만
진행하며, registry가 shutdown 원인으로 lock을 강제 해제하지 않는다.

### 4.5 Spring Actuator surface

기존 read-only endpoint와 같은 `leaderElection` ID를 유지하되 두 endpoint bean을
동시에 만들지 않는다.

- `management.endpoint.leaderElection.enabled=true`이고
  `management.endpoint.leaderElection.actions.enabled`가 없거나 `false`면 기존
  `LeaderElectionStatusEndpoint`만 만든다.
- `actions.enabled=true`일 때만 `LeaderElectionActionEndpoint`를 만들고,
  내부 status reader를 통해 기존 GET response를 그대로 제공한다. 두 클래스가
  모두 `@Endpoint(id = "leaderElection")`가 되더라도 Actuator registry에는
  하나만 등록된다.
- action endpoint는 기존 `@ReadOperation`과 additive
  `@WriteOperation release(@Selector lockName: String)`를 제공한다. request
  body로 token, leader ID, force flag를 받지 않는다.
- action endpoint bean은 core `LeaderManagementActionRegistry`가 없으면
  생성하지 않거나 startup에서 명시적으로 fail-fast한다. registry를 자동으로
  만들어 빈 성공을 가장하지 않는다.
- `management.endpoints.web.exposure.include`에 `leaderElection`이 없으면
  endpoint가 HTTP로 노출되지 않는 기존 규칙을 유지한다.
- Spring Security authentication/authorization은 library가 설치하지 않는다.
  운영 문서와 integration test는 `/actuator/leaderElection/**`에 인증, role 또는
  network allow-list를 적용해야 한다고 명시한다. management server를 별도
  interface/port에 바인딩하는 경우에도 같은 경계를 적용한다.

Spring property metadata에는 다음 기본값과 위험 설명을 추가한다.

```properties
management.endpoint.leaderElection.actions.enabled=false
```

기본값에서는 write operation bean과 route가 존재하지 않아 accidental exposure가
없다. `true`로 바꾸는 것은 인증을 설치하는 동작이 아니며, 노출 설정과 security
설정은 별도로 검증해야 한다.

### 4.6 Ktor surface

`LeaderElectionPluginConfig`에 다음 additive 설정을 추가한다.

```kotlin
var managementActionRouteEnabled: Boolean = false
var managementActionRouteGuard: (suspend ApplicationCall.() -> Boolean)? = null
var managementActionRegistry: SuspendLeaderManagementActionRegistry? = null
```

- POST action은 기존 `managementRoutePath` 아래의
  `/{lockName}` selector로 설치한다. GET status와 달리
  `managementRouteEnabled && managementActionRouteEnabled`가 모두 true여야
  route가 존재한다.
- `managementActionRouteEnabled=true`인데 registry 또는 guard가 없으면
  startup validation에서 `IllegalStateException`으로 fail-fast한다. action flag가
  `false`인 동안에는 guard/registry를 검사하지 않는다. library가 인증 성공을
  추정하지 않는다.
- guard가 false면 `403`과 `AUTHORIZATION_REQUIRED` 형태의 sanitized response를
  반환하고 registry를 호출하지 않는다. guard 예외는 500으로 전파하지 않고
  `AUTHORIZATION_FAILED`로 기록하며 action mutation은 하지 않는다.
- 애플리케이션은 Ktor `authenticate("management")` scope, mTLS/API gateway,
  management interface allow-list 중 하나 이상을 구성한 뒤 guard를 연결한다.
  library는 Ktor auth provider, credential, network ACL을 설치하거나 문서상
  public route를 안전하다고 주장하지 않는다.
- response JSON은 Spring과 같은 `action`, `outcome`, `mutationAttempted` 필드만
  사용하고 token, exception, backend metadata를 포함하지 않는다. 기존 GET
  JSON은 변경하지 않는다.
- suspend registry를 사용하므로 route handler는 blocking registry나
  `runBlocking`을 호출하지 않는다. selector는 wildcard/percent-decoding 재조합
  없이 Ktor의 경로 파라미터 하나로 처리한다.

## 5. 상태 전이와 failure mode

### 5.1 action truth table

| registry 상태 | pre-check | release 호출 | post-check | outcome | mutation |
| --- | --- | --- | --- | --- | --- |
| 등록 없음 | - | 없음 | - | `NOT_REGISTERED` | false |
| 같은 이름 2개 이상 | - | 없음 | - | `AMBIGUOUS` | false |
| 하나 등록 | `NOT_HELD` | 없음 | - | `NOT_HELD` | false |
| 하나 등록 | `UNKNOWN` | 없음 | - | `OWNERSHIP_UNKNOWN` | false |
| 하나 등록 | `HELD` | 성공 | `NOT_HELD` | `RELEASED` | true |
| 하나 등록 | `HELD` | 성공 | `HELD/UNKNOWN` | `RELEASE_UNCONFIRMED` | true |
| 하나 등록 | `HELD` | 예외 | - | `RELEASE_FAILED` | true |
| 빈/공백 이름 | - | 없음 | - | `INVALID_LOCK_NAME` | false |

`RELEASE_UNCONFIRMED`는 재시도/강제해제를 의미하지 않는다. operator는 상태 GET,
backend diagnostics, 애플리케이션 로그의 sanitized code를 별도로 확인해야 한다.

### 5.2 위험과 완화

| failure mode | 위험 | 완화와 검증 |
| --- | --- | --- |
| write surface 기본 노출 | 외부 요청이 운영 lock을 변경 | 두 property/config 모두 기본 false; default 404와 Actuator bean absence test |
| 이름만으로 force release | 다른 JVM lease 훼손 | process-local registration + `HELD` pre-check + backend handle release만 허용 |
| 같은 lock의 동시 lease | 어떤 lease를 해제할지 모호 | exactly-one 규칙, `AMBIGUOUS`, no backend call test |
| lease가 action 직전에 만료 | stale owner를 해제했다고 오인 | pre/post ownership check, `UNKNOWN`/`RELEASE_UNCONFIRMED` 분리 |
| release 중 transport 오류 | 상태와 결과 불일치 | sanitized `RELEASE_FAILED`, 예외 비노출, handle idempotence test |
| registration close race | 새 handle을 잘못 해제 | identity token reservation과 per-lock linearization test |
| action registry shutdown | registry가 backend를 임의 조작 | close는 admission만 닫고 registration/lease owner가 cleanup |
| Ktor guard 미설정 | 인증 없는 POST route | guard required/fail-closed, `AUTHORIZATION_REQUIRED` test와 README 경고 |
| Spring exposure 설정 누락 | endpoint가 생각과 다르게 노출/비노출 | Actuator exposure condition과 security 문서/metadata test |
| raw token/exception 응답 | credential/cardinality 노출 | response DTO field allow-list, `toString`/JSON redaction test |
| `runIfLeader`와 혼동 | action이 이미 종료된 action lock을 조작 | public request lease만 지원한다는 KDoc/README와 not-registered test |
| suspend 경로 blocking | Ktor event loop 지연 | suspend registry native call, `runBlocking` 금지 정적 검사 |

## 6. API/ABI와 호환성

- core의 신규 enum, result, registry는 additive public API다. 기존 elector와
  `LeaderLeaseHandle` descriptor를 변경하지 않는다.
- `LeaderManagementActionResult`는 data class copy/constructor를 의도적으로
  단순하게 유지하고, backend 내부 객체를 property로 노출하지 않는다.
- Spring의 기존 `LeaderElectionStatusEndpoint` public constructor와
  `LeaderElectionStatusResponse` ABI를 유지한다. status reader extraction은
  internal implementation detail이다.
- Ktor의 기존 `LeaderElectionManagementRegistry`, GET route, plugin defaults와
  JSON field를 유지한다. 새 config property만 추가한다.
- 새 API는 core에 Spring/Ktor dependency를 끌어오지 않는다. Spring/Ktor adapter와
  metadata/README 변경은 각 모듈에서만 수행한다.
- core registry는 single-leader `LeaderLeaseHandle`만 받고 group handle이나
  내부 `LeaderLockHandle`을 받지 않는다. 향후 group action은 별도 issue로 분리한다.

## 7. 테스트와 acceptance criteria

### 7.1 Core

- 유일한 `HELD` handle이 `RELEASED`가 되고 `release`와 post-check가 한 번씩만
  실행되는지 검증
- `NOT_HELD`, `UNKNOWN`, 미등록, 중복, invalid name이 backend 호출 없이 반환되는지
  검증
- release 예외가 `RELEASE_FAILED`로 정제되고 `Error`는 삼켜지지 않는지 검증
- 같은 handle 중복 registration, 서로 다른 handle 동시 registration,
  registration close/action race를 identity 및 linearization test로 검증
- registry `close()`가 lease를 release하지 않고 이후 action admission만 막는지 검증
- blocking/suspend registry가 같은 truth table과 sanitized result를 유지하는지 검증
- 결과 `toString`/직렬화에 token, exception message, backend payload가 없는지 검증

### 7.2 Spring

- `actions.enabled` 기본값에서 write operation bean이 없고 POST가 노출되지 않는지
  검증
- `actions.enabled=true`에서 read와 write가 같은 `leaderElection` ID로 충돌하지
  않고 selector action이 registry result를 반환하는지 검증
- registry가 없거나 Actuator exposure가 없을 때 fail-closed 동작 검증
- Spring Security가 library에서 자동 설치되지 않으며 문서의 application-owned
  security boundary가 metadata/README에 있는지 검증
- 기존 status response와 public JVM descriptor 회귀 검증

### 7.3 Ktor

- 기존 GET default disabled/JSON/custom path test가 그대로 통과하는지 검증
- `managementActionRouteEnabled=false` 또는 management GET disabled에서 POST가
  404인지 검증
- guard/registry 미설정 및 guard false에서 `403`/sanitized result와 no mutation을
  검증
- guard true + suspend registry의 `RELEASED`, `AMBIGUOUS`, `UNKNOWN` response와
  cancellation 경계를 검증
- handler가 `runBlocking`이나 blocking release를 사용하지 않는지 정적/코드 검사

### 7.4 문서와 정적 검증

- `leader-core`, `leader-spring-boot`, `leader-ktor` README EN/KO에 opt-in,
  ownership-proof, auth/network boundary, `runIfLeader` 비대상, failure outcome을
  추가
- public KDoc은 한국어로 작성하고 `requireNotBlank`, `requireNotNull` 등
  bluetape4k helper와 기존 assertion 패턴을 사용
- `git diff --check`, targeted Gradle tests, `detekt`, Kotlin terminology audit를
  통과

## 8. 구현 순서와 stop condition

1. core result/registry와 deterministic fake handle test를 TDD로 추가한다.
2. Spring status reader 분리, conditional endpoint bean, property metadata와
   Actuator integration test를 추가한다.
3. Ktor suspend registry, config/guard/POST route와 route test를 추가한다.
4. runtime registration handoff와 cleanup race를 연결하고 core/spring/ktor
   targeted test를 실행한다.
5. README EN/KO, KDoc, review artifact를 갱신하고 static analysis 및 전체 관련
   build를 실행한다.

다음 중 하나가 발견되면 구현을 중단하고 이슈/설계를 재검토한다.

- backend handle이 `ownershipStatus()`를 제공하지 않아 ownership proof를 만들 수
  없음
- runtime registration이 동일 handle의 identity를 보존하지 못함
- Spring/Ktor framework가 같은 endpoint ID를 동시에 등록하도록 강제함
- 인증/네트워크 경계를 library가 소유해야만 안전해지는 요구가 새로 생김
- release 후 post-check가 backend semantics상 결정 불가능하여 `UNKNOWN`과
  `RELEASED`를 구분할 수 없음

이 문서의 선택 범위를 넘는 force refresh, stale cache delete, group action,
backend-wide mutation은 별도 issue로 등록한다.
