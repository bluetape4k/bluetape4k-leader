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
   no-mutation outcome을 반환한다. 포화·종료 상태도 각각
   `ACTION_ADMISSION_REJECTED`와 `REGISTRY_CLOSED`로 명시한다. release 이후 확인 실패는 별도의
   `RELEASE_UNCONFIRMED`로 표시하고 성공으로 가장하지 않는다. force-steal,
   backend-wide delete, 임의 metadata 삭제는 구현하지 않는다.
4. Spring과 Ktor write surface는 각각 명시적인 opt-in과 애플리케이션 소유의
   인증/네트워크 경계를 갖는다. 기존 Spring `leaderElection` read/JMX status는
   유지하고 write action은 별도 web endpoint ID로 추가한다.
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
  `@Endpoint(id = "leaderElection")`와 `@ReadOperation`으로 상태와 기존 JMX
  status를 노출한다. action은 이 bean을 대체하지 않고 별도
  `@WebEndpoint(id = "leaderElectionActions")` bean으로 추가해야 한다.
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
  소유한다. 이번 issue에서는 runtime이 active handle의 registry registration을
  자동으로 만들지 않는다. runtime handoff와 terminal cleanup registration은
  bounded awaitable completion을 별도 issue에서 설계하며, registry가 lease
  lifecycle을 대신 소유하거나 shutdown 때 임의 release하지 않는다.

### 2.2 공식 자료

- Spring Boot custom endpoint는 `@Endpoint`와 operation annotation으로
  노출되며, write operation은 명시적인 `@WriteOperation`으로 선언한다.
  <https://docs.spring.io/spring-boot/reference/actuator/endpoints.html>
  <https://docs.spring.io/spring-boot/api/java/org/springframework/boot/actuate/endpoint/annotation/WriteOperation.html>
- Spring Boot web endpoint와 JMX endpoint는 별도 discoverer/exposure 경계를
  갖는다. 이번 action은 `@WebEndpoint`로 web만 허용한다.
  <https://docs.spring.io/spring-boot/reference/actuator/jmx.html>
  <https://docs.spring.io/spring-boot/api/java/org/springframework/boot/actuate/endpoint/web/annotation/WebEndpoint.html>
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
    REGISTRY_CLOSED,
    ACTION_IN_PROGRESS,
    ACTION_ADMISSION_REJECTED,
    ACTION_TIMED_OUT,
}

data class LeaderManagementActionResult(
    val action: LeaderManagementAction,
    val outcome: LeaderManagementActionOutcome,
    val mutationAttempted: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class LeaderManagementRegistrationOutcome {
    ACCEPTED,
    INVALID_LOCK_NAME,
    CAPACITY_REJECTED,
    REGISTRY_CLOSED,
}

class LeaderManagementRegistration(
    val accepted: Boolean,
    val outcome: LeaderManagementRegistrationOutcome,
) : AutoCloseable {
    override fun close() { /* idempotent token close */ }
}
```

`LeaderManagementActionResult`에는 token, backend exception, raw leader identity,
thread 정보가 포함되지 않는다. `mutationAttempted`는 phase가
`RELEASE_STARTED`에 선형화되었는지만 나타낸다. 따라서 `RELEASED`의 값은 항상
`true`이고, `INVALID_LOCK_NAME`, `NOT_REGISTERED`, `AMBIGUOUS`, `NOT_HELD`,
`OWNERSHIP_UNKNOWN`, `ACTION_IN_PROGRESS`, `ACTION_ADMISSION_REJECTED`,
`REGISTRY_CLOSED`의 값은 항상 `false`다. `RELEASE_UNCONFIRMED`와
`RELEASE_FAILED`는 release callback 진입 뒤에만 `true`이며, pre-check/예약 단계
실패는 `false`다. `ACTION_TIMED_OUT`은 timeout 시점에 release phase가 시작되지
않았으면 `false`, 시작됐으면 `true`다. `RELEASED`가 아니면 어떤 응답도 성공을
주장하거나 안전한 재시도를 보장하지 않는다.

lock 이름 입력은 public registry의 `register` 경계에서
`isManagementActionLockName`/`requireManagementActionLockName` helper로 검증한다.
이 helper는 기존 `validateLockName`의 backend 공통 규칙을 변경하지 않고, action
surface에만 ASCII `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` 규칙을 적용한다.
구현은 `io.bluetape4k.support.requireNotBlank`, `requireLe`, `requireGt` 등
bluetape4k helper를 조합하며 raw Kotlin `require`로 수치 계약을 우회하지 않는다.
management route가 전달한 빈/공백 selector는 registry에 도달하기 전에
`INVALID_LOCK_NAME`으로 변환하며 backend를 호출하지 않는다. 이름의 canonicalization이나
wildcard expansion은 하지 않는다. 기존 애플리케이션이 128자를 초과하거나 이
allow-list 밖의 lock name을 이미 사용한다면 action registration이
`INVALID_LOCK_NAME`으로 거부된다. migration은 lock 이름을 안전한 alias로 등록하고
기존 이름을 단계적으로 drain한 뒤 제거하는 방식으로 수행하며, 이번 issue는 기존
backend lock을 이름만으로 변환하거나 rename하지 않는다.

`isManagementActionLockName`은 registration/selector에서 예외 없이 boolean을
반환해 typed rejection을 만들고, `requireManagementActionLockName`은 startup
configuration이나 명시적인 application validation에서만 `requireNotBlank` 계열
예외를 발생시킨다. 두 helper는 같은 pattern을 공유하며 caller가 둘 중 하나를
선택해 규칙을 우회할 수 없다.

### 4.2 Blocking registry

`io.bluetape4k.leader.LeaderManagementActionRegistry`는 process-local registry다.
생성 시 선택적인 `LeaderManagementActionObserver?`를 주입할 수 있으며 기본값은
`null`이다. observer는 core surface(`CORE`)로 sanitized event를 전달하고, Spring/Ktor
adapter는 필요할 때 surface 값을 덧씌운 application-owned observer를 연결한다.

```kotlin
class LeaderManagementActionRegistry(
    observer: LeaderManagementActionObserver? = null,
) : AutoCloseable {
    fun register(handle: LeaderLeaseHandle): LeaderManagementRegistration
    fun release(lockName: String): LeaderManagementActionResult
    fun registeredLockNames(): List<String>
    /** 현재 회복되지 않은 quarantine reservation 수입니다. */
    fun quarantinedCount(): Int
    fun closeAndDrain(): Boolean
    override fun close()
}
```

생성자는 양의 유한 `actionTimeout`(기본 `5.seconds`, hard maximum `30.seconds`),
양의 유한 `cleanupGrace`(기본 `30.seconds`, hard maximum `30.seconds`),
`maxInFlightActions`(기본 16, hard maximum 256), `actionQueueCapacity`(기본 32,
hard maximum 1024), `maxRegistrations`와 `closeTimeout`을 받는다. registry는 기존
core `LeaseOperationScheduler`의 bounded `ThreadPoolExecutor`/`AbortPolicy` 패턴을
내부에서 재사용하고 scheduler를 소유한다. `maxRegistrations`는 active registration
token 수이며 기본 1024, hard maximum 65,536이다. `closeTimeout`은 기본 `5.seconds`,
hard maximum `30.seconds`다. 모든 수치 설정은 `requireGt`, `requireLe`,
`requirePositiveNumber`, `requireFinite` 등 bluetape4k helper로 양수·유한·hard
maximum 이내인지 검증하고 위반 시 startup/configuration 단계에서 실패한다. `register`와
registration token `close`는 backend I/O와 대기를 하지 않고 O(1) admission만 수행한다.
cap을 초과한 반복 등록을 포함한 모든 등록은 entry를 만들지 않는 idempotent no-op
token을 반환한다.

`register`는 `LeaderManagementRegistration`으로 admission 결과를 즉시 알린다.
정상 등록은 `accepted=true/ACCEPTED`, 잘못된 이름은
`accepted=false/INVALID_LOCK_NAME`, token cap 초과는
`accepted=false/CAPACITY_REJECTED`, `QUIESCING`/`CLOSED`는
`accepted=false/REGISTRY_CLOSED`다. 거부 token의 `close()`도 안전한 no-op이며,
호출자는 거부된 handle을 자신의 lifecycle에서 즉시 release해야 한다.

애플리케이션은 registration 결과를 확인하고 token을 lease와 같은 lifecycle에 묶는다.
action이 lease를 먼저 해제해도 application work는 취소되지 않으며, 진행 중인 작업,
외부 side effect, coroutine/job을 중단하거나 rollback하지 않는다. 따라서 호출자는
자신의 cancellation/rollback 정책을 별도로 조정하고 handle의 idempotent release를
사용해야 한다.

```kotlin
val registration = registry.register(handle)
if (!registration.accepted) {
    handle.release()
    return
}
try {
    doWork() // management action은 lease만 해제하며 application work는 취소하지 않는다.
} finally {
    handle.release()
    registration.close()
}
```

`CAPACITY_REJECTED`, `INVALID_LOCK_NAME`, `REGISTRY_CLOSED`는 token을 숨은 성공으로
취급하지 않는다. `registration.close()`는 accepted/rejected 어느 쪽에서도 여러 번
호출할 수 있다.

계약은 다음과 같다.

- `register`는 handle object identity를 기준으로 reference-counted entry를 만든다.
  같은 handle을 반복 등록하면 token마다 ref-count가 증가하고 각 token의
  idempotent `close()`가 하나씩 감소시킨다. 마지막 token이 닫힐 때만 entry를
  제거하며, 서로 다른 handle이 같은 `lockName`을 가지면 별도 entry로 보관한다.
  `maxRegistrations`는 이 token 각각을 계산하므로 반복 등록도 cap을 우회할 수
  없으며, cap 초과 no-op token의 `close()`도 idempotent하다.
- 등록 token의 `close()`는 registry entry만 제거하고 lease를 release하지 않는다.
  lease lifecycle은 handle 소유자가 계속 책임진다. registry `close()`도 모든
  registration을 닫아 action admission을 멈출 뿐 backend lock을 임의로 해제하지
  않는다.
- lock별 동시 action은 한 번만 진행한다. 다른 action이 이미 같은 lock의
  registration을 reserve한 동안에는 `ACTION_IN_PROGRESS`를 반환하고 두 번째
  backend 호출을 하지 않는다.
- 서로 다른 lock action도 bounded scheduler의 active/queue cap을 초과하면 대기하지
  않고 `ACTION_ADMISSION_REJECTED`를 반환한다. executor는 `CallerRunsPolicy` 같은
  caller-thread 실행 정책을 사용하지 않으며, `RejectedExecutionException`은 같은
  no-mutation outcome으로 정제한다. `actionTimeout`은 함수 진입 시점부터 측정하므로
  admission 대기가 response deadline을 늘리지 않는다.
- `release(lockName)`는 먼저 정확히 하나의 active registration을 선형화한다.
  등록 0개는 `NOT_REGISTERED`, 2개 이상은 `AMBIGUOUS`다.
- 유일한 handle의 `ownershipStatus()`가 `HELD`가 아니면 backend release를
  호출하지 않는다. `NOT_HELD`와 `UNKNOWN`을 구분하여 각각 결과를 반환한다.
- registry mutex는 lookup/reservation 직후 해제한다. `ownershipStatus()`,
  `release()`, post-check 같은 backend callback은 mutex 밖의 action executor에서
  실행하므로 lock A의 지연이 lock B의 등록/해제를 막지 않는다.
- action worker는 `ADMITTED -> PRECHECK -> RELEASE_STARTED -> POSTCHECK ->
  TERMINALIZED` phase CAS를 사용한다. deadline/cancellation/close가 경합하면
  phase CAS를 먼저 성공한 쪽만 다음 callback permit을 얻는다. callback permit은
  registry mutex 안에서 선형화하고 callback 자체는 mutex 밖에서 실행한다. close
  terminalization 이후 permit을 새로 얻을 수 없으며, 이미 permit을 얻은 callback만
  끝까지 실행한 뒤 counter를 정리한다.
- `HELD`를 확인한 뒤 handle의 idempotent `release()`를 최대 한 번 호출하고,
  terminal completion을 bounded deadline 안에서 기다린 뒤 다시
  `ownershipStatus()`를 확인한다. 후속 상태가 `NOT_HELD`이고 release가
  terminalized된 경우에만 `RELEASED`다. 후속 상태가 `HELD` 또는 `UNKNOWN`이면
  `RELEASE_UNCONFIRMED`다.
- public handle의 release callback이 `RuntimeException`을 던져도 exception detail을
  외부로 보내지 않고 `RELEASE_FAILED`를 반환한다. pre-check 예외는
  `OWNERSHIP_UNKNOWN`, post-check 예외는 `RELEASE_UNCONFIRMED`로 정제한다. 기존
  built-in lifecycle이 backend 예외를 `UNKNOWN` 상태로 변환하는 경우에도 이 public
  mapping을 유지한다. `Error`는 core 경계에서 삼키지 않는다.
- `Error`가 어느 phase에서든 발생하면 caller에게 재전파하되 worker `finally`에서
  callback permit, lock reservation, in-flight counter를 반드시 정리한다. release가
  이미 시작된 뒤의 `Error`는 해당 reservation을 `QUARANTINED`로 남기고 post-check나
  자동 재시도를 시작하지 않는다. `Error` 정리 실패로 `ACTION_IN_PROGRESS`가 영구
  잔류하지 않도록 이 경로를 별도 regression test로 고정한다.
- pre-check 또는 release/post-check가 monotonic `actionTimeout`을 넘으면
  `ACTION_TIMED_OUT`을 반환한다. `actionTimeout`은 `release(lockName)` 진입 시점부터
  측정하며 executor queue에서 무기한 대기하지 않는다. release가 시작된 뒤 timeout이면
  `mutationAttempted=true`로 표시하고, 해당 lock reservation은 worker가 terminal
  completion을 확인하거나 `cleanupGrace`에 도달할 때까지 유지하여 즉시 재시도하지
  않는다. `cleanupGrace`에 도달하면 worker를 interrupt/cancel하고
  `RELEASE_UNCONFIRMED`와 격리 상태를 기록한다. JVM이 interrupt를 무시하는
  non-interruptible callback은 `QUARANTINED` reservation과 scheduler slot을 Future가
  실제로 종료할 때까지 점유한다. registration token을 닫아도 실행 중 callback을
  해제하거나 새 action을 허용하지 않으며, 자동 재시도도 하지 않는다. registry는
  강제 thread stop을 시도하지 않고 sanitized quarantine metric/log code만 남긴다.
  timeout 전에 release가 시작되지 않았다면 `mutationAttempted=false`다.
- registry가 `QUIESCING` 또는 `CLOSED`이면 새 action은 backend callback 없이
  `REGISTRY_CLOSED`를 반환한다. `CLOSED` 이후 이미 callback permit을 받은 worker만
  terminal completion을 마칠 수 있고 새로운 callback은 시작하지 않는다.
- registration 종료와 action이 교차해도 이미 reserve한 동일 handle만 처리한다.
  새로 등록된 handle로 자동 전환하거나 이름만으로 다른 lease를 해제하지 않는다.
- 등록 시 action-addressable lock-name 규칙(ASCII
  `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, `.`, `..`, `%`, `*`, `/`, `\\`, 제어문자
  금지)을 `requireNotBlank`와 bounded validation으로 함께 확인한다. 이 규칙을
  만족하지 않는 handle은 entry를 만들지 않으며 selector에서 같은 검증이 실패하면
  `INVALID_LOCK_NAME`을 반환한다.
- `registeredLockNames()`는 정렬된 이름만 반환하며 상태 endpoint의 lock-name
  source와 혼용하지 않는다. backend state를 다시 조회하는 메서드가 아니다.

### 4.3 Suspend registry

blocking release를 `runBlocking`으로 감싸지 않기 위해
`io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry`를 같은
outcome contract로 추가한다.

```kotlin
class SuspendLeaderManagementActionRegistry(
    observer: LeaderManagementActionObserver? = null,
) : AutoCloseable {
    fun register(handle: SuspendLeaderLeaseHandle): LeaderManagementRegistration
    suspend fun release(lockName: String): LeaderManagementActionResult
    fun registeredLockNames(): List<String>
    /** 현재 회복되지 않은 quarantine reservation 수입니다. */
    fun quarantinedCount(): Int
    suspend fun closeAndDrain(): Boolean
    override fun close()
}
```

`SuspendLeaderManagementActionRegistry`는 양의 유한 `actionTimeout`(기본 `5.seconds`,
hard maximum `30.seconds`), `cleanupGrace`(기본 `30.seconds`, hard maximum
`30.seconds`), 양의 정수 `maxInFlightActions`(기본 16, hard maximum 256),
`maxRegistrations`(기본 1024, hard maximum 65,536), `closeTimeout`(기본 `5.seconds`,
hard maximum `30.seconds`)을 받는다. 모든 수치 설정은
`requirePositiveNumber`, `requireFinite`, `requireGt`, `requireLe` 등 bluetape4k
helper로 검증하고
범위를 벗어나면 configuration 단계에서 실패한다. backend callback은 suspend 경로에서만
호출하고, registry가 소유한 bounded `SupervisorJob` scope에서
`maxInFlightActions`만큼만 action/cleanup worker를 admission한다. blocking executor나
`runBlocking`은 사용하지 않는다. 전체 action worker는 registry lifecycle에 구조화되며,
요청 coroutine에 매달린 child로 만들지 않는다. cap이 가득 차면 기다리지 않고
`ACTION_ADMISSION_REJECTED`를 반환한다.

`actionTimeout`은 `release(lockName)` 진입 시점부터 caller가 관찰하는 HTTP response의
hard deadline이다. registry는 lock reservation과 worker admission을 non-blocking으로
시도하고, global cap이 가득 차면 기다리지 않고 `ACTION_ADMISSION_REJECTED`를
반환한다. admission된 worker result는 이 deadline까지만 기다리며, deadline에
도달하면 release 시작 여부를 원자적으로 읽어 `ACTION_TIMED_OUT`을 즉시 반환한다.
release가 아직 시작되지 않았다면 worker를 취소하여 mutation 없이 terminalize한다.
`cleanupGrace`는 release가 이미 시작된 뒤 registry 소유
`NonCancellable` continuation이 backend release와 post-check의 terminal completion을
기다릴 수 있는 별도 hard deadline이다. response가 timeout으로 먼저 끝나도 해당
worker와 lock reservation은 terminal completion까지 in-flight로 계산되므로 새
action이 같은 handle을 재사용하지 않는다. `cleanupGrace`가 지나면 worker를
`UNKNOWN`으로 terminalize하고 reservation을 격리한다. 이 격리 worker는
`maxInFlightActions`를 계속 점유하며 registration token이 닫힐 때까지 자동 재시도하지
않는다. caller cancellation이 pre-check 전에 발생하면 `CancellationException`을
재전파하고 mutation하지 않는다. `HELD` 확인 뒤 release/post-check 중 cancellation은
response caller에 원래 cancellation을 재전파하지만, registry scope의 bounded
`NonCancellable` continuation은 한 번만 실행하며 caller response를 성공으로
변환하지 않는다.

두 registry는 결과 enum, lock별 exactly-one 규칙, ownership pre-check/post-check,
registration token semantics를 공유한다. suspend registry의 `release`와 cleanup은
registry 소유 scope에 구조화되고 caller cancellation에서 ownership을 성공으로
보고하지 않는다. registry 내부에서 blocking scheduler나 `runBlocking`을 사용하지
않는다.

### 4.4 Handle registration 수명

이번 issue의 management registry는 application이 명시적으로 등록한 request lease만
대상으로 한다. `LeaderRouteLeaseRuntime`의 `AdmissionBoundLeaseHandle`과 Ktor
plugin의 `leaderScheduled` action-scoped handle은 cleanup이 비동기이므로 #532에서
자동 등록하지 않는다. 이 runtime handoff는 bounded awaitable completion을 별도
issue에서 설계한다. 따라서 구현자는 runtime의 active handle을 이름만으로 registry에
넣거나 action surface가 이미 종료된 `runIfLeader`를 조작한다고 주장해서는 안 된다.

지원되는 명시적 registration의 수명은 다음과 같다.

```text
application lease acquired
    -> registry.register(handle)
    -> application owns handle + registration token
    -> management action may reserve and release the same handle
    -> application completion releases handle
    -> application terminal callback closes registration token exactly once
```

action이 handle을 release한 경우에도 정상 completion이 다시 `handle.release()`를
호출할 수 있다. handle의 기존 idempotent contract가 backend 호출을 at-most-once로
보장한다. registration token을 먼저 닫아 action을 숨긴 뒤 handle을 release하는
순서는 사용하지 않는다. shutdown은 신규 등록을 막고 in-flight action의 bounded
drain을 기다린 뒤 registry를 닫으며, registry가 shutdown 원인으로 lock을 강제
해제하지 않는다.

registry 상태는 `OPEN -> QUIESCING -> CLOSED`다. blocking registry의 `close()`는
admission gate를 `QUIESCING`으로 전환하고 신규 register/release를 거부하며,
in-flight action count가 0이 되거나 `closeTimeout`에 도달할 때까지 동기적으로
drain한다. suspend registry의 `close()`는 같은 gate를 즉시 전환하는 non-blocking
호출이고, `suspend fun closeAndDrain(): Boolean`이 registry-owned scope에서
`closeTimeout` 동안 drain을 기다린 뒤 성공 여부를 반환한다. `close()`만 호출한
경우에도 worker가 0개가 되면 자동으로 `CLOSED`가 되며, 애플리케이션 lifecycle은
`closeAndDrain()`을 호출해 bounded 종료를 확인해야 한다. timeout 뒤에는 남은 action을
`ACTION_TIMED_OUT`/`RELEASE_UNCONFIRMED`로 terminalize하고 registration entry를
격리한다. close timeout이 phase CAS보다 먼저 이기면 해당 worker는
`QUARANTINED`로만 전이하고 callback permit을 잃는다. 이미 permit을 받은 backend
callback은 완료될 때까지 counter를 점유할 수 있지만, 그 이후 phase에서는 새
callback을 시작하지 않는다. `CLOSED` 이후 새 action은 `REGISTRY_CLOSED` no-mutation
outcome이며, backend callback을 새로 시작하지 않는다. blocking/suspend registry 모두
close 전에 admission된 worker만 drain 대상이며 caller 소유 executor/scope를 종료하지
않는다. blocking 경로의 non-interruptible callback은 `cleanupGrace` 뒤
`Future.cancel(true)`를 best-effort로 시도하고 quarantine한다. registry-owned
scheduler의 `shutdownNow`도 best-effort이며, 실제 thread 종료는 Future가 종료된
뒤에만 capacity를 회복한다. suspend 경로는 registry-owned scope만 종료하고
application의 외부 scope는 건드리지 않는다. blocking `closeAndDrain()`은 같은
bounded drain을 수행해 성공 여부를 반환하고, `close()`는 이를 호출한 뒤 결과를
버리는 convenience API로 정의한다.

### 4.5 Spring Actuator surface

기존 read-only `LeaderElectionStatusEndpoint`와 JMX status의 ID
`leaderElection`을 유지하고, write action에는 별도 web endpoint ID를 사용한다.
두 bean은 action mode에서도 동시에 존재하며 ID가 겹치지 않는다.

- `management.endpoint.leaderElection.enabled=true`이면 기존
  `LeaderElectionStatusEndpoint`를 계속 만든다. `actions.enabled`가 없거나
  `false`이면 이 read-only bean만 존재하고 action bean/registry는 생성하지 않는다.
- `management.endpoint.leaderElection.enabled=true`인 상태에서
  `management.endpoint.leaderElection.actions.enabled=true`일 때만
  `LeaderElectionActionWebEndpoint`와 default `LeaderManagementActionRegistry`를
  만든다. parent endpoint가 disabled이면 `actions.enabled=true`여도 action을
  만들지 않고 fail-closed한다. action endpoint는 기존 status response를 복제하지
  않고 release 결과만 반환한다. `@WriteOperation release(@Selector lockName: String)`만
  제공하며 request body로 token, leader ID, force flag를 받지 않는다.
- action endpoint는 `@WebEndpoint(id = "leaderElectionActions")`만 사용하고
  `@Endpoint` annotation을 붙이지 않는다. 따라서 HTTP 경로는
  `/actuator/leaderElectionActions/{lockName}`이고, 기존
  `/actuator/leaderElection` GET와 JMX `leaderElection` status는 그대로 유지된다.
  `management.endpoints.jmx.exposure.include=*` 또는 wildcard 설정이어도 action
  write operation은 JMX에 등록되지 않는다. 이번 issue에서 write JMX는 지원하지
  않는다.
- action mode의 auto-configuration은 application bean이 없는 경우 registry가
  소유하는 bounded scheduler를 포함한 core `LeaderManagementActionRegistry`를
  생성한다. 생성/검증에 실패하면 startup을 fail-fast한다. application이 자체
  registry bean을 제공하면 auto-configuration은 그 bean을 대체하거나 별도
  executor를 만들지 않으며, custom registry의 lifecycle을 application이 소유한다.
- `management.endpoints.web.exposure.include`에 `leaderElection`이 없으면 기존
  status endpoint가 HTTP로 노출되지 않는다. action을 노출하려면 별도로
  `leaderElectionActions`를 include해야 하며, 어느 ID도 include하지 않으면 POST는
  Actuator discoverer 단계에서 노출되지 않는다.
- Spring Security authentication/authorization은 library가 설치하지 않는다.
  운영 문서와 integration test는 `/actuator/leaderElectionActions/**`에 인증, role
  또는 network allow-list를 적용해야 한다고 명시한다. management server를 별도
  interface/port에 바인딩하는 경우에도 같은 경계를 적용한다.

Spring property metadata에는 다음 기본값과 위험 설명을 추가한다.

```properties
management.endpoint.leaderElection.actions.enabled=false
management.endpoint.leaderElection.actions.timeout=5s
```

기본값에서는 write operation bean과 route가 존재하지 않아 accidental exposure가
없다. `actions.enabled=true`일 때 생성되는 default registry는 property의 timeout과
bounded concurrency/registration cap을 적용하고, hard bound를 벗어나면 startup에서
실패한다. Spring context 종료 순서는 registry-owned scheduler가 살아 있는 동안
`registry.closeAndDrain()`으로 bounded drain을 확인한 뒤 registry를 닫는 것이다.
custom registry를 제공하는 경우에도 application이 동일한 drain 결과를 확인해야
하며, scheduler를 먼저 닫아 in-flight action을 고립시키지 않는다. `true`로 바꾸는
것은 인증을 설치하는 동작이 아니며, web exposure와 Spring Security/JMX 설정은
별도로 검증해야 한다.

### 4.6 Ktor surface

`LeaderElectionPluginConfig`에 다음 additive 설정을 추가한다.

```kotlin
var managementActionRouteEnabled: Boolean = false
var managementActionRegistry: SuspendLeaderManagementActionRegistry? = null
var managementActionRoutePath: String? = null
```

- POST action은 자동 plugin route로 설치하지 않는다. 애플리케이션이 제공된
  `Route.leaderElectionManagementActionRoute(...)` extension을
  필수 `authenticate("management")` scope 안에서 명시적으로 호출해야 한다. extension은
  `managementActionRouteEnabled`가 true일 때만
  `${managementRoutePath}/actions/{lockName}` selector를 추가한다. 기존 GET parent와
  action parent를 분리해 root routing에 인증 없는 write route가 생기지 않는다.
- `managementActionRouteEnabled=true`일 때 애플리케이션은 registry를 명시적으로
  생성·주입해야 하며, null이면 startup validation에서 fail-fast한다. registry와 그
  내부 scope의 owner는 application이고 plugin은 registry를 생성하거나 종료하지
  않는다. `managementActionRoutePath`가 null이면 기존 `managementRoutePath`의
  trailing slash를 제거한 뒤 `/actions`를 붙인 값을 사용하고, 명시된 path도 같은
  절대 경로/정규화 검증을 통과해야 한다. action path는 GET status path와 구별되는
  canonical source를 가지며 문서와 테스트가 그 값을 고정한다.

권장 호출 shape는 다음과 같다.

```kotlin
fun Route.leaderElectionManagementActionRoute(
    path: String,
    registry: SuspendLeaderManagementActionRegistry,
    authorize: suspend ApplicationCall.() -> Boolean,
)

routing {
    authenticate("management") {
        leaderElectionManagementActionRoute(
            path = pluginConfig.managementActionRoutePath
                ?: pluginConfig.managementRoutePath.trimEnd('/') + "/actions",
            registry = requireNotNull(pluginConfig.managementActionRegistry),
            authorize = { principal<ManagementPrincipal>()?.canRelease == true },
        )
    }
}
```

extension은 `Route` receiver에서만 route를 만들며, plugin의 root
`Application.routing` 블록에서 POST를 자동 추가하지 않는다. 구현 signature의
`authorize: suspend ApplicationCall.() -> Boolean` 인자는 기본값이 없는 필수 인자다.
`authorize` callback은
이미 `authenticate` provider가 통과시킨 call에 대해 추가 role/tenant 검사를
수행한다.
- extension은 `authorize: suspend ApplicationCall.() -> Boolean` callback을
  추가로 받아 role/tenant allow-list를 확인한다. callback false는 `403`과 고정
  `AUTHORIZATION_DENIED`, callback 예외는 `500`과 고정
  `AUTHORIZATION_FAILED`를 반환하며 모두 `mutationAttempted=false`이고 registry를
  호출하지 않는다. `authenticate` provider가 없는 root 설치는 지원 경계 밖이며
  문서/정적 review에서 거부한다.
- Ktor auth provider, credential, network ACL은 library가 설치하지 않는다.
  애플리케이션은 반드시 `authenticate("management")` provider와 route scope를 먼저
  구성해야 한다. mTLS/API gateway, management interface allow-list는 그 위에 둘 수
  있는 추가 방어이며 `authenticate`를 대체하지 않는다. 인증 provider는
  미인증 요청을 `401`, 권한 없는 principal을 `403`으로 차단하며, extension
  integration test에서 두 상태와 callback/registry no-mutation을 검증한다.
- core action result response는 Spring과 같은 `action`, `outcome`,
  `mutationAttempted` 필드만 사용하고 token, exception, backend metadata를 포함하지
  않는다. 인증 callback 실패는 아래의 별도 `LeaderManagementRouteError` shape를
  사용한다. 기존 GET JSON은 변경하지 않는다.
- suspend registry를 사용하므로 route handler는 blocking registry나
  `runBlocking`을 호출하지 않는다. selector는 wildcard/percent-decoding 재조합
  없이 Ktor의 경로 파라미터 하나로 처리한다. canonical action path 아래에서
  `/` 또는 `%2F`가 Ktor routing matcher에 의해 selector로 전달되지 않고 route
  자체가 매칭되지 않으면 `404`로 끝나며 registry/backend는 호출하지 않는다.
  route가 매칭된 뒤 전달된 selector가 `%`, `*`, `..`, 제어문자 또는 overlong 값이면
  `400`의 `INVALID_LOCK_NAME` result를 반환한다. action-addressable lock name은
  ASCII `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`만 허용하고 `.`, `..`, `%`, `*`, `/`,
  `\\`, 제어문자와 128 byte 초과 입력은 registration/selector 모두
  `INVALID_LOCK_NAME`으로 거부한다. lock name에 secret을 넣지 않는다고 문서화하고
  access log/exception에는 raw selector와 credential을 남기지 않는다.

Ktor lifecycle은 `ApplicationStopping`의 non-suspend event handler에서
`closeAndDrain()`을 호출하거나 `runBlocking`으로 감싸지 않는다. 애플리케이션이
서버를 멈추는 suspend coordinator를 소유하고, engine stop 전에 registry drain을
확인한다.

```kotlin
suspend fun Application.stopLeaderManagementGracefully(
    registry: SuspendLeaderManagementActionRegistry,
    gracePeriodMillis: Long = 1_000,
    timeoutMillis: Long = 5_000,
) {
    check(registry.closeAndDrain()) {
        "leader management registry did not drain within closeTimeout"
    }
    engine.stopSuspend(gracePeriodMillis, timeoutMillis)
}
```

서비스의 shutdown coordinator는 `ApplicationStopping` 전에 이 suspend 함수를
호출하고, 반환값이 `false`이면 종료를 계속하되 quarantine metric/log와 운영
경보를 남긴다. `ApplicationStopping`/`ApplicationStopped` event는 관찰용 hook으로만
사용하며, registry scope와 application 외부 coroutine scope를 임의로 취소하지
않는다.

인증 실패는 core action result가 아니라 Ktor adapter의
`LeaderManagementRouteError` DTO로 표현한다.

```kotlin
enum class LeaderManagementRouteErrorCode {
    AUTHORIZATION_DENIED,
    AUTHORIZATION_FAILED,
}

data class LeaderManagementRouteError(
    val error: LeaderManagementRouteErrorCode,
    val mutationAttempted: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

이 DTO는 401 provider response를 대신하지 않으며, callback 단계에서만 사용한다.
Spring authentication failure는 Spring Security가 소유하고 core result와 섞지
않는다.

### 4.7 HTTP response와 재시도 계약

두 adapter의 action response는 `Content-Type: application/json`을 사용하고 request
body를 받지 않는다. JSON field는 core result의 `action`, `outcome`,
`mutationAttempted` 세 개로 allow-list하며 enum은 정의된 대문자 문자열 그대로
직렬화한다. Spring은 `WebEndpointResponse<LeaderManagementActionResult>`로 상태
코드를 지정하고, Ktor는 기존 dependency 경계를 유지하기 위해 허용된 field만
수동 JSON으로 `respondText`한다. exception, token, backend payload, raw selector는
어느 response에도 포함하지 않는다.

| outcome | HTTP | mutationAttempted | 기본 재시도 정책 |
| --- | ---: | --- | --- |
| `RELEASED` | 200 | true | 재시도하지 않음 |
| `INVALID_LOCK_NAME` | 400 | false | 입력 수정 후에만 재시도 |
| `NOT_REGISTERED` | 404 | false | 자동 재시도하지 않음; registration 확인 |
| `AMBIGUOUS`, `NOT_HELD`, `ACTION_IN_PROGRESS` | 409 | false | 자동 재시도하지 않음; 상태 재관찰 |
| `ACTION_ADMISSION_REJECTED` | 429 | false | bounded backoff 재시도 가능; `Retry-After`는 선택적 |
| `OWNERSHIP_UNKNOWN` | 503 | false | 자동 재시도하지 않음; GET/backend diagnostics와 lifecycle 확인 |
| `RELEASE_UNCONFIRMED`, `RELEASE_FAILED` | 503 | true | 자동 재시도하지 않음; GET/backend diagnostics와 lifecycle 확인 |
| `REGISTRY_CLOSED` | 503 | false | registry 재기동/교체를 확인한 뒤 수동 판단 |
| `ACTION_TIMED_OUT` | 504 | phase에 따라 false 또는 true | 기본 자동 재시도하지 않음; worker terminalization을 확인한 뒤에만 운영 판단 |

표의 `mutationAttempted` 값은 core phase 규칙의 재진술이며 adapter가 임의로
변경하지 않는다. 특히 `ACTION_TIMED_OUT`에서 `false`라는 응답만으로 즉시 같은
lock을 재호출하지 않는다. registry가 기존 worker를 terminalize했음을 관찰한
뒤에만, 애플리케이션이 별도 idempotence/fencing 정책에 따라 수동 판단한다.
`RELEASE_UNCONFIRMED`와 `RELEASE_FAILED`는 backend 상태가 결정되지 않았으므로
성공으로 바꾸거나 강제 release로 승격하지 않는다. `ACTION_ADMISSION_REJECTED`의
429는 mutation이 없을 때만 사용할 수 있는 bounded overload 신호다.

### 4.8 관측성 및 감사 경계

registry는 선택적인 application-owned sanitized observer를 받을 수 있다. observer
event는 다음처럼 제한된 enum과 값만 가진다.

```kotlin
enum class LeaderManagementActionSurface { CORE, SPRING, KTOR }
enum class LeaderManagementActionPhase { ADMITTED, PRECHECK, RELEASE_STARTED, POSTCHECK, TERMINALIZED, QUARANTINED }

data class LeaderManagementActionObservation(
    val surface: LeaderManagementActionSurface,
    val outcome: LeaderManagementActionOutcome,
    val phase: LeaderManagementActionPhase,
    val mutationAttempted: Boolean,
    val quarantined: Boolean,
)

fun interface LeaderManagementActionObserver {
    fun onResult(observation: LeaderManagementActionObservation)
}
```

event에는 `surface`(`CORE`, `SPRING`, `KTOR`), `outcome`, `mutationAttempted`,
제한된 `phase`, `quarantined`만 포함하고 lock name, actor, credential, token,
thread, exception message, backend metadata는 포함하지 않는다.
observer 예외는 action result나 cleanup을 변경하지 않으며, observer 설치 자체는
기본 비활성이다. #535 audit export adapter는 이 sanitized event를 외부 감사
저장소로 내보내는 후속 범위로 다루고, #532 core는 durable actor audit log를
저장하지 않는다.

quarantine이 발생하면 다음 저카디널리티 metric/log 계약을 사용한다.

- counter: `bluetape4k.leader.management.quarantine`
- counter tags: `reason`(`cleanup-timeout`, `non-interruptible`, `callback-error`,
  `close-timeout`), `phase`(`precheck`, `release`, `postcheck`, `close`),
  `surface`(`core`, `spring`, `ktor`)
- active gauge: `bluetape4k.leader.management.quarantine.active` (동일한
  `reason`, `phase`, `surface` tag 집합)
- sanitized structured log code: `leader-management-quarantine`, 같은 세 tag와
  registry 상태만 기록

lock name, actor identity, request ID, credential, token, exception text는 metric
tag나 log field에 넣지 않는다. Future/coroutine이 실제로 종료되어 reservation이
회복되면 active gauge를 감소시키고 `quarantinedCount()`를 갱신한다. 회복 전에는
token close나 새 요청이 capacity를 우회하지 않는다. 이 관측성 계약은 운영자가
quarantine을 발견하고 인스턴스 drain/교체를 판단할 수 있게 하지만, durable audit
보존·export는 #535에서 별도로 승인한다.

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
| 같은 lock action 진행 중 | - | 없음 | - | `ACTION_IN_PROGRESS` | false |
| global action cap 초과 | - | 없음 | - | `ACTION_ADMISSION_REJECTED` | false |
| action deadline 초과 | 미확정 | 미확정 | 미확정 | `ACTION_TIMED_OUT` | phase에 따라 |
| `QUIESCING`/`CLOSED` | - | 없음 | - | `REGISTRY_CLOSED` | false |
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
| 느린 ownership/release callback | Actuator thread pool 고갈 | registry-owned bounded scheduler/deadline, mutex 밖 callback, timeout reservation test |
| action admission 포화/거부 | 요청이 무기한 대기하거나 caller thread에서 실행 | registry-owned bounded scheduler/admission, `ACTION_ADMISSION_REJECTED`, `CallerRunsPolicy` 금지 test |
| interrupt 무시 callback | quarantine 뒤 executor/lock capacity가 고갈 | `Future.cancel(true)` best-effort, slot/registration 유지, sanitized quarantine metric와 recovery runbook |
| callback `Error` | reservation이 영구히 in-flight로 남음 | `finally` 정리 후 Error 재전파, release 후에는 quarantine, Error regression test |
| registration close race | 새 handle을 잘못 해제 | identity token reservation과 per-lock linearization test |
| registry close와 action 교차 | scheduler 종료 뒤 callback 실행 | phase/callback permit CAS, `OPEN -> QUIESCING -> CLOSED`, in-flight drain/close timeout test |
| action registry shutdown | registry가 backend를 임의 조작 | close는 admission만 닫고 registration/lease owner가 cleanup |
| Ktor auth scope/guard 미설정 | 인증 없는 POST route | explicit `Route` extension + `authenticate` scope required, `AUTHORIZATION_DENIED/FAILED` test와 README 경고 |
| Spring exposure 설정 누락 | endpoint가 생각과 다르게 노출/비노출 | Actuator exposure condition과 security 문서/metadata test |
| raw token/exception 응답 | credential/cardinality 노출 | response DTO field allow-list, `toString`/JSON redaction test |
| `runIfLeader`와 혼동 | action이 이미 종료된 action lock을 조작 | public request lease만 지원한다는 KDoc/README와 not-registered test |
| suspend 경로 blocking | Ktor event loop 지연 | suspend registry native call, `runBlocking` 금지 정적 검사 |
| Ktor registry lifecycle 누락 | stop 뒤 worker/scope 잔류 | application-owned registry, `closeAndDrain()` before `ApplicationStopped` test |
| hostile selector | 다른 lock 매칭 또는 log injection | ASCII allow-list, `%`/delimiter/control/overlong negative test |
| JMX wildcard exposure | write operation의 MBean 노출 | `@WebEndpoint` action, JMX absence test, web/JMX 설정 분리 |

## 6. API/ABI와 호환성

- core의 신규 enum, result, registration token, observer, registry는 additive
  public API다. 기존 elector와 `LeaderLeaseHandle` descriptor를 변경하지 않는다.
- `LeaderManagementActionResult`는 data class copy/constructor를 의도적으로
  단순하게 유지하고, backend 내부 객체를 property로 노출하지 않는다.
- Spring의 기존 `LeaderElectionStatusEndpoint` public constructor와
  `LeaderElectionStatusResponse` ABI를 유지한다. status reader extraction은
  internal implementation detail이다.
- Ktor의 기존 `LeaderElectionManagementRegistry`, GET route, plugin defaults와
  JSON field를 유지한다. 새 config property, action route와
  `LeaderManagementRouteError` DTO만 additive로 추가한다.
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
- action timeout과 slow callback이 bounded executor를 고갈시키지 않고
  `ACTION_TIMED_OUT`/`RELEASE_UNCONFIRMED`로 끝나는지 검증
- blocking registry는 executor의 active/queue capacity를 초과하는 서로 다른 lock
  요청이 기다리지 않고 `ACTION_ADMISSION_REJECTED`를 반환하며 backend callback을
  호출하지 않는지 검증한다. registry-owned scheduler의
  `RejectedExecutionException`, `CallerRunsPolicy` 금지와
  `Future.cancel(true)`/quarantine 정책도 고정한다.
- suspend registry는 `maxInFlightActions + 1` 이상 동시에 요청될 때 bounded worker cap을
  초과한 요청이 기다리지 않고 `ACTION_ADMISSION_REJECTED`를 반환하며 backend callback을
  호출하지 않는지 검증한다.
- 같은 handle 중복 registration, 서로 다른 handle 동시 registration,
  registration close/action race를 identity 및 linearization test로 검증
- registry mutex가 backend callback 동안 풀려 있어 다른 lock의 register/release가
  차단되지 않는지 검증
- registry `close()`/`closeAndDrain()`이 lease를 release하지 않고 `QUIESCING` drain
  뒤 action admission만 막는지 검증하며, suspend 경로는 `runBlocking` 없이 bounded
  종료 결과를 반환하는지 검증한다. close timeout 직전 callback permit을 가진 late
  worker는 완료 후 counter만 정리하고, terminalization 이후 새 backend callback을
  시작하지 않는지 phase/CAS race test로 고정한다.
- `maxRegistrations`가 active registration token 기준으로 cap되고, 동일 handle의
  반복 registration이 cap을 우회하지 않으며 cap 초과 no-op token의 `close()`가
  idempotent한지 검증
- pre-check/post-check/release callback에서 `Error`가 발생해도 caller에 재전파되고
  reservation/counter는 `finally`에서 정리되며, release 이후에는 quarantine되어
  새 backend callback을 시작하지 않는지 검증
- blocking/suspend registry가 같은 truth table과 sanitized result를 유지하는지 검증
- 결과 `toString`/직렬화에 token, exception message, backend payload가 없는지 검증
- release action이 application work/coroutine/외부 side effect를 취소하지 않고,
  application이 idempotent handle release와 registration token close를 직접 수행하는지
  lifecycle contract test로 검증
- optional observer가 허용된 enum/boolean만 전달하고 observer 예외가 result·cleanup을
  바꾸지 않는지, quarantine active gauge가 실제 worker 회복 시 감소하는지 검증

### 7.2 Spring

- `actions.enabled` 기본값에서 write operation bean이 없고 POST가 노출되지 않는지
  검증
- `actions.enabled=true`에서 기존 `leaderElection` read/JMX endpoint와
  `leaderElectionActions` web endpoint가 동시에 존재하고 ID/path가 충돌하지 않으며
  selector action이 registry result를 반환하는지 검증
- action mode endpoint가 `@WebEndpoint`로만 노출되고 JMX wildcard/include 설정에서
  write operation이 생성되지 않는지 검증
- registry가 없거나 Actuator exposure가 없을 때 fail-closed 동작 검증
- Spring Security가 library에서 자동 설치되지 않으며 문서의 application-owned
  security boundary가 metadata/README에 있는지 검증
- 기존 status response, JMX status와 public JVM descriptor 회귀 검증

### 7.3 Ktor

- 기존 GET default disabled/JSON/custom path test가 그대로 통과하는지 검증
- `managementActionRouteEnabled=false` 또는 explicit extension 미설치에서 POST가
  404인지 검증
- `authenticate("management")` 밖의 route를 허용하지 않는 문서/정적 review와,
  인증 provider의 미인증 `401`, 권한 없는 principal `403`, callback false/exception의
  고정 error DTO 및 registry no-mutation을 검증
- authenticated route + suspend registry의 `RELEASED`, `AMBIGUOUS`, `OWNERSHIP_UNKNOWN`,
  `ACTION_TIMED_OUT`, `ACTION_ADMISSION_REJECTED`, `REGISTRY_CLOSED` response와
  cancellation 경계를 검증
- 모든 outcome의 HTTP status, JSON field allow-list, `mutationAttempted` 불변식과
  retry matrix를 Spring/Ktor 양쪽 integration test로 검증한다.
- canonical `${managementRoutePath}/actions/{lockName}` path와 custom path가 GET
  status path와 분리되는지 검증한다. `%2F`/`/`가 matcher 단계에서 route를 매칭하지
  않으면 `404`, 매칭된 hostile selector(`..`, `*`, control, overlong,
  Unicode/non-ASCII)는 `400 INVALID_LOCK_NAME`으로 끝나고 backend를 호출하지
  않는지 검증
- handler가 `runBlocking`이나 blocking release를 사용하지 않는지 정적/코드 검사
- application-owned registry가 `ApplicationStopped` 전에 `closeAndDrain()`을 호출하고
  caller scope가 취소된 뒤에도 registry worker가 `cleanupGrace`/quarantine 계약을
  지키는지 lifecycle test로 검증

### 7.4 문서와 정적 검증

- `leader-core`, `leader-spring-boot`, `leader-ktor` README EN/KO에 opt-in,
  ownership-proof, auth/network boundary, `runIfLeader` 비대상, failure outcome과
  quarantine/복구 절차를 추가
- source-of-truth manual EN/KO에도 동일한 운영 계약을 반영한다:
  `docs/manual/en/frameworks/spring-boot.md`,
  `docs/manual/ko/frameworks/spring-boot.md`,
  `docs/manual/en/frameworks/ktor.md`,
  `docs/manual/ko/frameworks/ktor.md`,
  `docs/manual/en/guides/observability-and-operations.md`,
  `docs/manual/ko/guides/observability-and-operations.md`. README는 manual로
  연결하고 서로 다른 기본값/HTTP status/path를 만들지 않는다.
- public KDoc은 한국어로 작성하고 `requireNotBlank`, `requireNotNull` 등
  bluetape4k helper와 기존 assertion 패턴을 사용한다. 예외 검증은
  `io.bluetape4k.assertions.assertFailsWith`를 사용하고 JUnit
  `assertThrows`, `kotlin.test.assertFailsWith`, `invoking { } shouldThrow`를
  새로 도입하지 않는다.
- release action은 lease만 바꾸고 application work/외부 side effect/coroutine을
  취소하지 않는다는 문장과 registration 거부 시 caller cleanup 예제를 README와
  manual에 포함
- HTTP status/JSON allow-list/retry matrix, canonical Ktor action path, Spring
  `leaderElection` JMX 보존과 `leaderElectionActions` web exposure를 README/manual
  integration test로 고정
- rollout/rollback runbook은 다음 순서를 포함한다: (1) actions/config와 route를
  비활성으로 배포하고 bean/route 부재 및 GET status를 확인, (2) management auth,
  role/network allow-list를 먼저 구성, (3) known handle 하나만 registration하고
  canary 인스턴스에서 action path를 확인, (4) `RELEASED`/409/429/503/504와
  quarantine metric를 관찰, (5) 문제가 있으면 flag를 false로 되돌리고 route를
  제거하되 force unlock/retry를 하지 않으며, (6) `RELEASE_UNCONFIRMED` 또는
  quarantine이면 재시도를 멈추고 GET/backend diagnostics를 확인한 뒤 application
  cleanup 후 인스턴스를 drain/교체한다.
- 관리 action은 운영 cold path이므로 별도 throughput 수치 계약을 추가하지 않는다.
  대신 registration/token close의 O(1) no-wait, bounded action/cleanup admission,
  slow callback stress와 timeout evidence를 필수로 삼고, 이 기준을 넘는 latency나
  allocation 회귀가 관찰되면 별도 benchmark issue로 분리한다.
- sanitized quarantine counter/gauge와 observer 예외 격리, #535 audit export 경계의
  단위/문서 검증을 추가
- `git diff --check`, targeted Gradle tests, `detekt`, Kotlin terminology audit와
  manual validation을 통과

## 8. 구현 순서와 stop condition

1. core result/registry와 deterministic fake handle test를 TDD로 추가한다.
2. Spring status reader 분리, registry-owned bounded scheduler bean의 조건부 생성과
   shutdown 순서, `leaderElectionActions` web endpoint bean, property metadata 및
   Actuator integration test를 추가한다.
3. Ktor suspend registry, application-owned config/guard/POST route와
   canonical action path/HTTP mapping과 `closeAndDrain()` lifecycle test를 추가한다.
4. 이번 issue에서 자동 runtime registration은 연결하지 않는다. 명시적 application
   registration의 cleanup race를 core/ktor targeted test로 고정하고, runtime handoff는
   후속 issue로 링크한다.
5. README EN/KO, manual EN/KO, KDoc, review artifact를 갱신하고 static analysis 및
   전체 관련 build를 실행한다.

다음 중 하나가 발견되면 구현을 중단하고 이슈/설계를 재검토한다.

- backend handle이 `ownershipStatus()`를 제공하지 않아 ownership proof를 만들 수
  없음
- runtime registration이 동일 handle의 identity를 보존하지 못함
- Spring/Ktor framework가 기존 status ID와 별도 action web ID를 동시에 등록하도록
  허용하지 않음
- 인증/네트워크 경계를 library가 소유해야만 안전해지는 요구가 새로 생김
- release 후 post-check가 backend semantics상 결정 불가능하여 `UNKNOWN`과
  `RELEASED`를 구분할 수 없음
- backend release callback이 `cleanupGrace`와 interrupt를 무시하고 executor slot을
  계속 점유하여 quarantine capacity가 회복되지 않음

이 문서의 선택 범위를 넘는 force refresh, stale cache delete, group action,
backend-wide mutation은 별도 issue로 등록한다.
