# Issue #607 요청별 lease 획득·연장·해제 설계

## 문제와 결과

현재 `leader-spring-boot`의 `STATE` route guard는 현재 프로세스의 리더 상태만
읽는 passive 검사다. 요청이 처리되는 동안 해당 route의 lease를 소유하지 않으며,
`LeaderElector.runIfLeader`도 action의 호출 범위 안에서만 acquire와 release를
완료한다. 이 구조로는 여러 Spring MVC/WebFlux 인스턴스가 같은 route를 처리할 때
요청 전체를 하나의 lease 소유권으로 묶을 수 없다.

Issue #607의 결과는 다음과 같다.

- `leader-core`에 기존 `runIfLeader`와 독립된 additive `LeaderLeaseAcquirer`와
  `SuspendLeaderLeaseAcquirer` 계약을 추가한다.
- 모든 publishable single-leader backend의 동기·suspend 구현과 async/virtual
  wrapper가 같은 acquire/watchdog/min-lease/release lifecycle을 사용하도록
  리팩터링한다.
- 기존 상태 데이터인 `LeaderLease`를 보존하고, 별도의
  `LeaderLeaseHandle`/`SuspendLeaderLeaseHandle`이 request completion 경계까지
  ownership을 유지한다. 중복 `close`/`release`는 한 번으로 정리하며 acquire
  thread와 completion thread가 달라도 안전하게 release한다.
- Spring route guard에 `authority-mode=LEASE`를 추가한다. MVC는
  `preHandle`에서 lease를 저장하고 `afterCompletion`에서 해제하며, WebFlux는
  `Mono.usingWhen`으로 complete/error/cancel 세 경로의 cleanup을 공통화한다.
- 정상 contention과 acquire 실패는 기존 rejection status와 빈 body로 fail-closed
  하고, handler 예외·Reactor 취소·coroutine 취소에서도 ownership이 누수되지
  않는다.
- 기존 `STATE`/`CUSTOM` passive route guard와 `LeaderElector`의 source/binary
  surface는 유지한다. `LEASE`는 기본 비활성이고 redirect metadata 정책과
  조합하지 않는다.

## 범위와 제외

### 목표

- action-scoped lock lifecycle을 request-scoped lease lifecycle로 재사용할 수
  있는 framework-neutral core capability를 제공한다.
- `LeaderElectionOptions`의 `waitTime`, `leaseTime`, `minLeaseTime`,
  `autoExtend`, `useDbTime` semantics를 backend마다 다르게 복제하지 않고
  기존 elector가 가진 설정으로 그대로 적용한다.
- lease handle이 backend token, internal address, raw exception을 public
  response나 로그에 노출하지 않도록 한다.
- MVC와 WebFlux가 같은 rejection/exception/cancellation 계약을 갖도록 한다.
- listener, event, micrometer decorator, tenant wrapper가 acquire와 release를
  한 번씩만 관찰하도록 한다.

### 제외

- `LeaderElector`, `AsyncLeaderElector`, `VirtualThreadLeaderElector`,
  `SuspendLeaderElector`의 기존 abstract method signature 변경.
- `LeaderGroupElector`/`SuspendLeaderGroupElector`의 semaphore-style group
  lease. Issue #607의 route slot은 single-leader `LeaderSlot`만 사용한다.
- `StrategicLeaderElector` 계열의 candidate scoring과 별도 전략 lease.
- `STATE`/`CUSTOM` authority의 passive state semantics, Issue #606 redirect
  URI policy, public leader endpoint discovery.
- route마다 다른 `LeaderElectionOptions`를 동적으로 덮어쓰거나 요청별 TTL을
  전달하는 설정. elector bean은 immutable options를 단일 source of truth로
  유지하며, route에는 blocking/admission/cleanup의 안전 상한만 둔다. 다른 TTL
  정책이 필요하면 별도 elector bean을 명시적으로 선택한다.
- lease가 request 중간에 상실되었을 때 이미 전송된 HTTP response를 되돌리거나
  handler를 강제 중단하는 기능. watchdog 결과는 기존 event/metric 경계로
  관찰하며, response commit 이후의 안전한 rollback은 제공하지 않는다.
- 새로운 dependency, module, BOM, publishing, CI workflow 추가.

Spring MVC와 non-suspend WebFlux `LEASE` factory의 elector 선택은 동기
`LeaderLeaseAcquirer` capability를 사용한다. `SuspendLeaderLeaseAcquirer` 경로는
동일한 `route-guard.elector-bean` 이름으로 선택된 bean이
`SuspendLeaderLeaseAcquirer` capability도 제공할 때만 활성화하며,
`SuspendLeaderLeaseLifecycle`의 `NonCancellable` cleanup과
complete/error/cancel 경계를 사용한다. 선택된 bean이 suspend capability를
제공하지 않으면 `LEADER_ROUTE_ELECTOR_SUSPEND_LEASE_UNSUPPORTED`로 fail-fast한다.
bean 이름 없음/이름 오류/복수 후보는 기존 selector error code를 유지한다. sync
elector를 suspend elector로 임의 cast하거나 `runBlocking`으로 감싸는 동작은
제공하지 않는다. 따라서 Exposed R2DBC 같은 suspend-only bean은 native suspend
WebFlux route에서만 선택할 수 있고, MVC 또는 non-suspend WebFlux route에는
별도의 sync capability bean이 필요하다.

## 현재 근거와 외부 자료

- Live Issue #607: `feat(leader-spring-boot): 요청별 lease 획득 route mode 추가`.
  assignee는 `debop`, milestone은 `1.0.0`, labels는
  `enhancement`, `feature`, `integration`, `security`, `spring`이다. Issue body는
  MVC/WebFlux request-to-lease lifecycle, wait/lease/minimum lease,
  auto-extension, timeout, exception/Reactor/coroutine cancellation release,
  duplicate instrumentation 방지와 resource-leak tests를 요구한다.
- Epic #700은 OPEN 상태이며 Spring train `SPRING-R-02`에서 #607을 request-to-
  lease lifecycle로 기록한다. predecessor #606은 이미 exact merge head
  `56a33db44e22fb137e205119dd853f153cff3402`로 완료되었다.
- Issue #537은 passive route guard와 request-scoped lease를 분리하고, state guard가
  lease를 mutate하지 않아야 한다고 명시한다.
- 현재 route source는
  `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/` 아래의
  `LeaderRouteAuthorityRuntime`, `LeaderMvcRouteGuardFactory`,
  `LeaderWebFluxRouteGuardFactory`, `LeaderRouteGuardProperties`다.
- 현재 core source의 `LeaderLockHandle`, `LeaderLeaseAutoExtender`,
  `LockExtender`, `AbstractLocalLeaderElector`, `LocalSuspendLeaderElector`는
  이미 handle/watchdog/min-lease/cleanup 패턴을 제공하지만, handle release가
  public request resource로 분리되어 있지 않다.
- 격리 worktree baseline은 `origin/develop`의
  `56a33db44e22fb137e205119dd853f153cff3402`이며,
  `./gradlew --no-daemon :bluetape4k-leader-spring-boot:test`는
  `BUILD SUCCESSFUL`이었다.
- Spring MVC 공식 문서는 `HandlerInterceptor.afterCompletion`을 요청 완료 후
  resource cleanup 경계로 설명하고, async request는 async lifecycle과 함께
  고려하도록 안내한다.
  - https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/handlermapping-interceptor.html
- Reactor 공식 API 문서는 `Mono.usingWhen`이 subscriber별 resource를 만들고
  complete/error/cancel 경로에서 비동기 cleanup을 수행하는 연산임을 정의한다.
  - https://docs.spring.io/projectreactor/reactor-core/docs/3.4.5/api/reactor/core/publisher/Mono.html
- 위 외부 자료는 2026-08-23에 확인했으며, framework lifecycle을 확인하는 근거로만
  사용한다. lease ownership과 backend release safety는 이 repository의 core
  contract와 backend 구현이 최종 권위다.

## 선택한 설계

### 1. Core additive lease capability

기존 `LeaderElector.runIfLeader`를 변경하지 않고 다음 public capability를
`leader-core`에 추가한다. `tryAcquire`가 `null`을 반환하는 것은 정상 contention이며
예외가 아니다.

동기 surface의 정확한 FQN은 `io.bluetape4k.leader.LeaderLeaseAcquirer`와
`io.bluetape4k.leader.LeaderLeaseHandle`이며, `LeaderElectionOptions`, `LeaderSlot`,
`ExtendOutcome`도 같은 package에 있다. coroutine surface의 정확한 FQN은
`io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer`와
`io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle`이다. 두 파일 모두
`kotlin.time.Duration`을 사용하고, handle의 `acquiredAt`은
`java.time.Instant`다.

```kotlin
// leader-core: io.bluetape4k.leader
import java.time.Instant
import kotlin.time.Duration

interface LeaderLeaseAcquirer {
    val configuredOptions: LeaderElectionOptions
    fun tryAcquire(lockName: String): LeaderLeaseHandle?
    fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle?
}

// leader-core: io.bluetape4k.leader.coroutines
import java.time.Instant
import kotlin.time.Duration

interface SuspendLeaderLeaseAcquirer {
    val configuredOptions: LeaderElectionOptions
    suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle?
    suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle?
}
```

두 overload 모두 capability contract의 필수 항목이다. `tryAcquire(lockName)`은
항상 acquirer가 생성 시 캡처한 `configuredOptionsBaseline`의
`nodeId`를 사용해 `LeaderSlot(lockName, configuredOptionsBaseline.nodeId)`로
정규화하여 audit identity로 보존한다. `tryAcquire(slot)`은
caller의 `slot.leaderId`를 그대로 보존한다. 모든 built-in elector와 capability
adapter는 이 mapping을 직접 제공하며, 임의의 leader id를 생성하거나 audit 없는
상태를 성공으로 반환하지 않는다. 기존 `LeaderElector`의 lock-name/slot bridge와
달리 request lease에는 default identity bridge가 없고, capability를 직접 구현하는
custom elector도 두 overload를 모두 명시해야 한다.

`LeaderLease`는 현재 `leader-core`의 상태 데이터/data class이므로 이름을
재사용하지 않는다. 동기 lifecycle handle은 다음 public surface를 갖는다.

```kotlin
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

coroutine backend는 suspend cleanup을 blocking bridge로 숨기지 않도록 별도 handle을
제공한다.

```kotlin
interface SuspendLeaderLeaseHandle {
    val lockName: String
    val auditLeaderId: String
    val acquiredAt: Instant

    suspend fun extend(lockAtMostFor: Duration): ExtendOutcome
    suspend fun ownershipStatus(): LeaseOwnershipStatus
    suspend fun isStillHeld(): Boolean
    suspend fun release()
}
```

`LeaseOwnershipStatus`는 `io.bluetape4k.leader`의 additive enum이며
`HELD`, `NOT_HELD`, `UNKNOWN` 세 값을 갖는다. `ownershipStatus()`는 backend를
재호출하지 않고 lifecycle이 마지막으로 확정한 fencing 결과를 반환한다.
`isStillHeld()`는 호환성을 위한 편의 메서드로
`ownershipStatus() == HELD`일 때만 `true`를 반환한다. 정상 conditional release,
명시적인 `NotHeld`, fencing mismatch는 `NOT_HELD`이고, transport timeout,
ordinary release error, `CLOSED_WITH_LEAKS` residual은 `UNKNOWN`이다. 따라서
`false`가 ownership 불확실성을 숨기지 않으며, 호출자는 불확실성을 구분해야 한다.

기존 `ExtendOutcome`의 backend 결과는 유지하고, 새 capability 경로에는 payload가
없는 additive `ExtendOutcome.Rejected` object를 추가한다. 이는 bounded serial
queue admission만 거부된 경우에 사용하며 backend ownership을 판정하지 않는다.
기존 exhaustive `when` consumer는 이 새 branch를 재컴파일 때 추가해야 한다는
source compatibility 경계를 API 문서와 migration test에 기록한다.

handle contract는 다음과 같다.

- `release`/`close`는 idempotent하다. 한 handle의 backend release와
  `LeaderElectionListener.notifyRevoked`는 최대 한 번만 실행한다.
- request/exchange holder는 공통 handoff 상태를 사용한다. 기존 holder가 없을 때
  request/exchange factory는 먼저 `tryReserve(maxConcurrentAcquires)`로
  `acquireAttemptPermit`을, 이어서 `tryReserve(maxAcquireQueueDepth)`로
  `acquireQueueSlot`을 확보한다. 둘 중 하나라도 실패하면 이미 확보한 자원을
  반환하고 backend 호출 전 fixed admission rejection을 반환한다. 이 두 reserve가
  성공한 경우에만 `ACQUIRING` holder를 설치하며, holder-install race에서 패배한
  요청과 기존 `ACQUIRING`에 join하는 duplicate는 shared permit/queue slot을 새로
  만들지 않고 자신의 waiter reference만 보유한다. `ACQUIRING`이 설치되는
  순간 생성된 shared acquire attempt/future가
  `(acquireAttemptPermit, acquireQueueSlot, cleanupReservation, residualSlot)` resource
  tuple의 canonical owner가 되어 네 자원을 함께 관리한다. 중복 instrumentation은
  같은 future에 join/wait하고 새 acquire,
  shared acquire-attempt permit, queue slot, reservation을 만들지 않는다. 각 요청은 waiter reference만
  소유하므로 최초 owner가 취소되어도 다른 waiter가 남아 있으면 shared future와
  `handoffReservation`을 취소하거나 반환하지 않는다. 마지막 waiter가 취소된
  경우에만 shared attempt를 `ACQUIRING -> CANCELLED -> CLOSED`로 원자 전이하고,
  future가 성공하면 `ACQUIRING -> PUBLISHED` CAS에서 cleanup reservation과
  residualSlot을 새 private holder/handle로 원자적으로 transfer하고, acquireAttemptPermit과 queue slot은
  성공 callback이 terminal이 되는 즉시 반환한다. 이 transfer가 완료되기 전에는
  취소·deadline callback도 terminal이 될 때까지 두 shared 자원을 유지하며, 그
  terminal completion에서 permit과 queue slot을 정확히 한 번 반환한다. holder-install
  race의 패배 경로는 backend callback을 시작하지 않고 두 자원을 즉시 반환한다.
  handle을 공개하지 않는다. transfer된 composite cleanup reservation은
  `(cleanupReservation, residualSlot)`이며 이후 `PUBLISHED/LIVE`
  handle이 `CLOSING -> CLOSED`가 될 때까지 handle이 소유하며, 그 수는
  `effectiveActiveCapacity` 상한(동시에 `maxActiveLeases` 상한)에 포함된다. 취소·deadline 경로에서는 transfer하지 않고
  shared future가 네 resource tuple을 모두 유지한다.
  acquire callback이 그 뒤 늦게 완료되면 동일
  reservation을 사용해 `NonCancellable` emergency cleanup에서 conditional release를
  수행하고, callback이 끝난 뒤에만 reservation을 반환한다.
  callback deadline이 먼저 지나면 deadline arbiter는 private acquire attempt/future를
  원자적으로 `lateCallback=DISCARD`와 deadline terminal로 표시하고 request/exchange
  factory에 `AcquireDeadlineSignal`을 전달한다. holder를
  `CLOSED_WITH_LEAKS`로 전이하는 CAS와 holder reservation 정리는 이 signal을
  소비하는 request/exchange factory 소유 경로에서만 수행한다. handoff reservation은
  late callback의 transport timeout 반환과, 반환된 handle의
  conditional discard/release가 끝날 때까지 `in-flight residual`로 유지한다. 그
  뒤에만 남은 reservation을 회수하며, callback이 timeout contract를 지키지 않는
  backend는 `LEASE` capability를 노출하지 않는다. 대기 중인 중복 요청은 holder가
  `CLOSED` 또는 `CLOSED_WITH_LEAKS`가 된 뒤에만 새 holder를 설치할 수 있다.
  acquire가 공개된 뒤에는 `PUBLISHED/LIVE -> CLOSING -> CLOSED` 순서를 사용하며,
  release winner 하나만 cleanup reservation을 소비한다. `CLOSED` 이후의 late
  extend/release는 backend를 호출하지 않는다.
- backend `release` 호출은 handle마다 at-most-once다. scheduler 제출/transport
  대기는 emergency lane에서 재시도할 수 있지만 이미 호출한 backend release를
  hidden retry하지 않는다. ordinary release failure는 TTL/fencing으로 stale
  ownership을 격리하고 sanitized metric/diagnostics로 남기며, route primary
  signal은 유지한다.
- public handle은 `LIVE -> CLOSING -> CLOSED` 원자 상태와 acquisition마다 새로
  발급된 불변 fencing token/generation을 가진다. `ACQUIRING`은 public handle이
  아직 존재하지 않는 private acquire-attempt/holder 상태이며, 공개 시점에만
  `PUBLISHED -> LIVE` handle이 만들어진다. `release`/`extend`는 lock name만으로
  동작하지 않고 이 token/generation을 조건부 비교하므로, 만료 후 새 owner가
  재획득한 뒤 오래된 handle이 새 ownership을 해제하거나 연장할 수 없다.
- release는 acquire thread에 종속되지 않는다. thread-bound backend는 backend가
  제공하는 owner-thread-id/token API 또는 owner-independent conditional API를
  사용하고, 지원할 수 없는 custom implementation은 `LEASE` capability를
  구현하지 않는다. local backend도 `ReentrantLock.unlock()`을 completion thread에서
  호출하지 않고 tokenized registry primitive를 사용한다.
- release 순서는 watchdog 중지 -> `minLeaseTime` 잔여 시간 대기 -> backend
  ownership release -> revoked event/listener 한 번이다. acquire가 완료되지 않은
  상태에는 handle을 반환하지 않으며, 취소와 acquire 완료가 경합하면 late-acquired
  handle을 즉시 conditional release한다.
- `extend`는 기존 `ExtendOutcome`을 사용하고, raw token이나 delegate를 노출하지
  않는다. `lockAtMostFor`는 acquisition 시 캡처한
  `configuredOptionsBaseline.leaseTime`을 초과할 수 없다.
  `autoExtend=true`면 기존 `LeaderLeaseAutoExtender`의 required-start 경로가
  handle의 delegate를 감시하며, watchdog admission/rejection은 조용한 no-op이
  아니라 acquire 실패와 rollback으로 처리한다. watchdog은 handle release에서
  반드시 닫힌다.
- `extend`와 `release`는 handle별 bounded serial executor에서만 선형화한다.
  mutex를 잡은 채 backend operation을 기다리지 않는다. `release` winner는
  `LIVE -> CLOSING`을 확정한 뒤 serial queue의 in-flight extend completion을
  operation id로 join하고, queue가 비는 순간 conditional backend release를 한 번
  호출하고 `CLOSED`로 전이한다. `CLOSING/CLOSED`에 진입한 뒤의 extend는
  `ExtendOutcome.NotHeld`로 즉시 끝나며 backend를 호출하지 않는다. queue drain이
  cleanup deadline을 넘기면 release call을 추가하지 않고 handle은 terminal `CLOSED`,
  holder/runtime은 `CLOSED_WITH_LEAKS` residual로 terminalize한다. 이때 handle이
  보유한 composite cleanup reservation `(cleanupReservation, residualSlot)`은 handle의 `CLOSING -> CLOSED`와 holder/runtime의
  `CLOSED_WITH_LEAKS` terminal CAS가 묶인 operation에서
  `ResidualLeaseRegistry`의 private residual owner로 원자 transfer한다. residual
  owner는 `in-flight` admission counter와 `drain.residual`을 계속 보유하고,
  conditional release/TTL/fencing 결과 또는 transport timeout terminal이 확인될
  때까지 pool로 반환하지 않는다. 그 결과가 확인된 뒤에만 registry가 reservation을
  회수한다. 새 acquire는 residual registry reservation을 사용할 수 없으며,
  drain timeout/deploy 종료 때에도 registry entry와 fencing/TTL 보호를 먼저
  관찰한다. 동시 호출은 하나의 terminal transition만 backend에 도달하며, 나머지는
  idempotent outcome과 observation으로 끝난다.
- serial executor 안에서 재진입한 `release()`는 자기 자신이 끝나기를 기다리는
  self-join을 하지 않는다. release winner가 이미 executor 소유자이면 terminal
  continuation을 같은 호출 스택에서 실행하고, 그 외에는 non-droppable release
  lane에 한 번만 위임한다. extend 작업은 bounded queue를 사용하며 enqueue가
  포화되면 backend를 호출하지 않고 고정 `LEADER_ROUTE_LEASE_EXTEND_REJECTED`
  observation과 `ExtendOutcome.Rejected`를 반환한다. 이 결과는 ownership을
  `NOT_HELD`로 바꾸지 않으며 watchdog은 다음 bounded tick에서 다시 시도한다.
- 정상 contention은 `null`, lease 만료나 backend ownership 상실은
  `ExtendOutcome.NotHeld`/기존 observation으로 표현한다. route adapter는 이
  결과를 response body나 `Location`으로 변환하지 않는다.
- public sync/suspend `release()`/`close()`의 ordinary backend release failure는
  예외로 던지지 않고 `ownershipStatus=UNKNOWN`과 fixed sanitized observation으로
  기록한 뒤 정상 반환한다. 이 결정은 `Unit` 반환 surface에서 직접 호출자와 route
  cleanup의 동작을 동일하게 만든다. `CancellationException`은 bounded
  `NonCancellable` cleanup 뒤 원래 취소 신호를 보존해 전파하고,
  `InterruptedException`은 interrupt flag를 복구한 뒤 전파하며, fatal `Error`는
  cleanup을 시도한 뒤 숨기지 않고 전파한다. route cleanup은 ordinary failure를
  항상 observation으로만 변환해 handler primary response/error를 덮지 않는다.
  cleanup failure는 primary signal을 절대 대체하지 않는다. primary가
  `CancellationException`, `InterruptedException` 또는 fatal `Error`이면 원래
  primary를 전파하고 cleanup failure는 sanitized suppressed observation으로만
  남긴다. primary가 없고 cleanup이 fatal `Error`이면 그 `Error`를 전파하며,
  primary가 없고 ordinary cleanup error이면 `UNKNOWN`을 관찰한 뒤 정상 반환한다.
  primary가 없는 interruption은 interrupt flag를 복구한 뒤 전파한다. route-facing
  response/log/metric/event에는 원본 cleanup exception, class, message 또는
  suppressed chain을 기록하지 않는다.
- `SuspendLeaderLeaseHandle.release`와 부분 acquire rollback은
  `withContext(NonCancellable)` 경계에서 실행하되 무기한 실행하지 않는다. request
  cleanup은 `min(acquiredAtMonotonic + maxLeaseLifetime, request cleanup deadline)`,
  context close는 `min(acquiredAtMonotonic + maxLeaseLifetime, close deadline)`을
  operation deadline으로 전달한다. public `acquiredAt: Instant`는 audit/display만
  사용하고 elapsed/deadline 계산은 monotonic `TimeSource`/`nanoTime` 기준 시각으로
  수행하여 wall-clock jump를 허용하지 않는다. backend adapter는 이 deadline을 transport timeout으로
  적용해야 하며, deadline 초과는 `LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT` 관측과
  residual fencing/TTL 보호로 종결한다. 취소된 coroutine에서도 conditional backend
  release와 revoked observation은 정확히 한 번 수행한다.
- public `release()`/`close()`는 caller가 직접 종료할 때 사용할 bounded default
  deadline을 적용한다. MVC `afterCompletion`/`AsyncListener`, WebFlux
  `Mono.usingWhen`의 complete/error/cancel, late-acquire discard, Spring context
  close는 no-arg public method를 우회하지 않고 내부
  `LeaseCleanupBoundary.releaseWithin(monotonicDeadline,
  compositeCleanupReservation=(cleanupReservation, residualSlot))`를 호출한다. 이
  boundary가 같은 deadline을 backend `release` callback의 transport
  timeout까지 전달하며, deadline-aware cleanup을 구현하지 않는 backend는
  `LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED`로 제외한다.
  `releaseWithin`의 boundary 결과는 `RELEASED`, `NOT_HELD`,
  `RESIDUAL_TRANSFERRED` 세 가지로만 고정한다. `RELEASED`/`NOT_HELD`는 backend
  callback이 terminal이 된 뒤 cleanup reservation과 residual slot을 pool에 반환하고
  `CLOSING -> CLOSED`를 완료한다. backend `release`의 raw `ERROR`/`TIMEOUT`와
  `stopWatchdog`의 raw `ERROR`/`TIMEOUT`은 boundary 결과가 아니라 내부
  `releaseOutcome`/`cleanup.timeout`/backend-call observation으로 정규화한다.
  backend call 전 submission exhaustion, `release` callback의 raw `ERROR`/`TIMEOUT`,
  또는 watchdog stop failure가 boundary deadline을 소진한 경우에는 cleanup
  reservation과 residual slot을 `ResidualLeaseRegistry`로 원자 transfer한 뒤
  `RESIDUAL_TRANSFERRED`를 반환한다. `stopWatchdog` failure가 있어도 release callback이
  deadline 안에 `RELEASED`/`NOT_HELD`로 terminal이 되면 그 boundary 결과를 유지하고
  watchdog failure만 내부 observation으로 남긴다. 이 transfer가 cleanup publisher와
  MVC terminal callback의 bounded completion이며, registry가 residual owner로서
  conditional release/TTL/fencing 또는 transport terminal을 계속 관찰한다. registry
  terminal 전에는 reservation을 pool에 반환하거나 새 acquire에 재사용하지 않는다.
- public no-arg `release()`/`close()`의 default deadline은 구현별 임의값이 아니라
  `LeaderLeaseDefaults.PUBLIC_RELEASE_TIMEOUT = 30s`와 acquisition 시점의
  `configuredOptionsBaseline.leaseTime` 중 짧은 쪽을 사용해
  `nowMonotonic + min(30s, configuredOptionsBaseline.leaseTime)`으로 계산한다. 이
  상수는 sync/suspend handle이 공유하고, route 경로의
  `releaseWithin(...)`은 이 기본값을 사용하지 않고 request/close deadline을
  우선한다. timeout이 지나면 handle은 `CLOSED`, ownership은 `UNKNOWN`,
  residual observation은 fencing/TTL 보호로 남긴다.
- 상태 전이의 단일 소유자는 surface별로 고정한다. holder의
  `ACQUIRING/CANCELLED/PUBLISHED/LIVE/CLOSING/CLOSED/CLOSED_WITH_LEAKS` 전이는 request/exchange
  factory가, handle의 `LIVE/CLOSING/CLOSED` 전이는 lifecycle helper가,
  route runtime의 `RUNNING/QUIESCING/DRAINING/DRAINED -> CLOSED` 또는
  `DRAINING -> CLOSED_WITH_LEAKS` 전이는
  shutdown coordinator가 소유한다. holder가 `PUBLISHED`를 성공시키는 순간에만
  handle을 `LIVE`로 공개하고, release winner가 holder와 handle을 같은
  `CLOSING` operation id로 연결한다.
- scheduler submission failure와 backend call failure는 구분한다. submission
  failure는 cleanup deadline 안에서 최대 두 번 재스케줄할 수 있지만, backend
  release call이 시작된 뒤의 오류/timeout은 hidden retry 없이 handle을 terminal
  `CLOSED`로 만들고 `releaseOutcome=ERROR|TIMEOUT`을 기록한다. 같은 terminal
  operation에서 composite cleanup reservation을 `ResidualLeaseRegistry`로 transfer하고,
  route holder와 runtime은 `CLOSED_WITH_LEAKS` residual로 분류한다. submission
  exhaustion이 deadline까지 회복되지 않는 경우에도 동일한 transfer를 수행한다.
  terminal handle의 `ownershipStatus()`는 마지막 확정 결과를 반환하고,
  `isStillHeld()`는 그 결과가 `HELD`인지에 대한 편의 판정만 수행한다. 새 acquire는
  `CLOSED`/`CLOSED_WITH_LEAKS` holder를 재사용하지 않는다.
- handle은 `Serializable`이 아니며 request/session에 직렬화하지 않는다. public
  값은 lock name과 audit id뿐이고 backend token, connection, executor는 private
  implementation state로 남긴다.

### 2. 공통 lifecycle과 backend migration

각 backend의 현재 `runIfLeader` 구현에서 acquire, handle 생성, watchdog 시작,
listener/event publish, action 실행, min-lease 대기, release를 분리하고 내부
`LeaderLeaseLifecycle`/`SuspendLeaderLeaseLifecycle` helper로 합친다. helper는
backend-specific callback만 받으며 새로운 public generic backend abstraction이나
reflection을 만들지 않는다. 구현 경계는 다음 내부 callback 계약으로 고정한다.

| callback | 입력 | 반환/규칙 |
|---|---|---|
| `acquire` | `LeaderSlot`, monotonic wait deadline, transport deadline | `BackendLease?`; non-null일 때만 token/generation·reservation을 발급하고 `null`은 handle 없는 contention |
| `extend` | acquisition token/generation, requested duration, monotonic deadline | `ExtendOutcome`; token mismatch는 `NotHeld`, queue admission은 `Rejected` |
| `release` | acquisition token/generation, monotonic deadline | `RELEASED`, `NOT_HELD`, `ERROR`, `TIMEOUT` 중 하나; 이미 시작한 call은 재시도하지 않음 |
| `isHeld` | acquisition token/generation, monotonic deadline | `HELD`, `NOT_HELD`, `UNKNOWN`; public handle에 raw backend 응답을 노출하지 않음 |
| `stopWatchdog` | acquisition token/generation, monotonic deadline, transport deadline | `STOPPED`, `NOT_RUNNING`, `ERROR`, `TIMEOUT` 중 하나; bounded stop failure만 관찰하고 backend release와 중복하지 않음 |

coroutine callback도 같은 입력/결과와 deadline을 사용하며 suspend 경계만 다르다.
모든 callback은 acquisition별 private token/generation과 absolute monotonic
deadline을 전달받아야 하고, transport timeout을 무시하거나 lock name만으로
release하는 구현은 capability 대상에서 제외한다. `release`/`isHeld` 오류는
내부 fixed observation code로 정규화하며 원본 exception/message를 route surface로
전달하지 않는다.
`stopWatchdog`도 같은 absolute monotonic/transport deadline을 사용하며, `TIMEOUT`
또는 `ERROR`는 watchdog을 더 기다리거나 backend release를 재호출하지 않고
bounded observation으로 종결한다. callback 결과는 lifecycle helper가 한 번만
소비하고 route surface에는 원본 오류를 노출하지 않는다.

backend call metric은 callback 결과를 다음처럼 정규화한다. `acquire`, `release`,
`extend`는 각각 같은 이름의 `phase`를 사용하고 `stopWatchdog`는 `phase=watchdog`를
사용한다. `acquire`가 `null`을 반환한 정상 contention은 `outcome=not-held`로
기록하고 route-level `skipped` observation은 별도로 남긴다. `SUCCESS`/`STOPPED`는
`outcome=success`, `NOT_HELD`/`NOT_RUNNING`은
`outcome=not-held`, `ERROR`는 `outcome=error`, `TIMEOUT`은 `outcome=timeout`으로
기록한다. `release`의 `ERROR`/`TIMEOUT`은 앞의 boundary 규칙에 따라 residual
transfer와 fixed timeout observation을 추가한다. `stopWatchdog`의 `ERROR`/`TIMEOUT`은
release callback이 deadline 안에 terminal이 되면 해당 boundary 결과를 유지하고
watchdog observation만 추가하며, deadline을 소진한 경우에만 residual transfer로
종결한다.

```text
tryAcquire
  -> waitTime bounded acquisition
  -> normal contention: null + skipped observation
  -> ownership acquired
       -> internal `LeaderLeaseHandle` + required watchdog
       -> elected observation exactly once
       -> caller owns `LeaderLeaseHandle`

release/close (any completion thread)
  -> idempotent state transition
  -> stop watchdog
  -> honor minLeaseTime
  -> conditional fencing-token/thread-safe release
  -> revoked observation exactly once
```

기존 action API는 다음처럼 같은 lifecycle을 사용한다.

```kotlin
    val lease = tryAcquire(slot) ?: return null
    return try {
        action()
} finally {
    lease.release()
}
```

실제 구현에서는 `try/finally`가 listener 중복을 만들지 않도록 lifecycle helper가
acquire/release observation의 유일한 소유자가 된다. `runAsyncIfLeader`와
virtual-thread elector는 같은 sync acquirer를 executor/virtual thread에서
호출하고, suspend elector는 suspend lifecycle을 사용한다.

기존 action API의 ownership bridge도 보존한다. `runIfLeader`/async/virtual/suspend
action은 lifecycle helper가 만든 내부 `LeaderLockHandle`을 기존 context
capture/push/pop 경계(`LockAssert`, `LockExtender`, AOP action context)에 계속
설치하고, action 종료 시 그 내부 handle을 정리한다. request API의
`LeaderLeaseHandle`/`SuspendLeaderLeaseHandle`은 별도 public resource로 유지하며
이 context에 직접 push하지 않는다. 따라서 explicit request lease가 기존
`LeaderLockHandle` context capture를 우회하지도, 동일 backend release/watchdog를
두 번 소유하지도 않는다.

`Listening*`, `Instrumented*`, tenant-scoped wrapper는 capability를 정적 타입으로
추측하지 않는다. 각 wrapper factory는 (a) delegate가
`LeaderLeaseAcquirer`/`SuspendLeaderLeaseAcquirer`인 경우에만
`LeaderLeaseCapabilityAdapter`/`SuspendLeaderLeaseCapabilityAdapter` view를
명시적으로 붙인 capability-preserving variant를 만들고, (b) 그 capability가
없으면 기존 `LeaderElector`/`SuspendLeaderElector` wrapper만 만들어 `LEASE`
selector가 `LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED`로 거부하도록 한다. 이
adapter는 delegate에서 startup 시 캡처한 `configuredOptionsBaseline`, `LeaderSlot` identity, handle lifecycle을
그대로 위임하고 자체 watchdog/release를 만들지 않는다. `LeaseCapabilityResolver`는
이 명시적 view만 따라가며 reflection이나 무조건적인 cast를 사용하지 않는다.
listener/micrometer observation은 lifecycle helper가 한 번만 발행하고 wrapper는
그 event를 전달만 한다. tenant scope는 lock identity만 바꾸되 caller의
`slot.leaderId`와 fencing token 소유권 범위를 넓히지 않는다.

다음 backend matrix를 구현 범위로 고정한다.

| 영역 | 대상 | lease capability | 검증 방식 |
|---|---|---:|---|
| core local | `LocalLeaderElector`, `LocalAsyncLeaderElector`, `LocalVirtualThreadLeaderElector`, `LocalSuspendLeaderElector` | 필수 | deterministic unit/contract |
| Redis | Lettuce와 Redisson sync/suspend 및 async/virtual delegate | 필수 | backend contract + cross-thread release |
| SQL | Exposed JDBC sync/virtual, Exposed R2DBC suspend | 필수 | DB/Testcontainers contract |
| document/cloud | MongoDB sync/suspend, DynamoDB sync/suspend/virtual | 필수 | backend contract + ownership loss |
| coordination | etcd sync/suspend/virtual, Consul sync/suspend, ZooKeeper sync/suspend | 필수 | integration contract |
| platform/cache | Kubernetes Lease sync/suspend, Hazelcast sync/suspend | 필수 | integration contract |
| wrappers | listener, micrometer, tenant-scoped, async/virtual wrappers | delegate | no duplicate observation |
| group/strategic | `LeaderGroupElector`, `Strategic*` | 제외 | 기존 contract 회귀만 |

backend가 acquire를 수행하지만 completion thread에서 안전하게 release할 API를
제공하지 않는 경우에는 억지 bridge나 `runBlocking`을 추가하지 않는다. 해당
elector는 기존 `runIfLeader`만 지원하고 `LEASE` startup selection에서
`LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED`로 fail-fast한다. built-in backend는 모두 이 기준을
통과하도록 migrate한다. Redisson은 owner thread id만으로 ownership을 판별하지
않는다. acquire 자체를 단일 Lua `EVAL`로 수행하여 서버가 (a) 아직 만료되지 않은
owner field를 확인하고, (b) acquisition마다 새 난수 fencing token/version을
생성·저장하고, (c) owner field와 TTL을 같은 atomic operation으로 설정한 뒤,
(d) 발급한 token/version을 반환해야 한다. 클라이언트가 token을 만든 뒤 별도
`SET`/`PEXPIRE`를 수행하는 두 단계 acquire는 허용하지 않는다. 이후 extend, unlock,
`minLeaseTime` expiry cleanup도 같은 token/version 비교 Lua operation으로 원자
처리한다. 따라서 동일 Redisson owner field가 TTL 만료 후 재사용되어도 이전
handle은 새 owner를 건드릴 수 없다. token-based backend는 동일하게 acquisition별
ownership token을 private handle에 보존한다.
local registry도 generation을 비교하는 release/extend만 허용하며 lock name만 받는
primitive는 `LEASE` 경로에서 사용하지 않는다. token 비교와 deadline timeout을
지원하지 않는 backend는 capability를 노출하지 않는다.

### 3. Spring mode와 configuration

`LeaderRouteAuthorityMode`에 `LEASE`를 additive enum 값으로 추가한다. 이 변경의
호환성 약속은 기존 `STATE`/`CUSTOM` 값에 대한 binary compatibility와 기존
non-exhaustive source 사용 보존으로 한정한다. Kotlin에서 `when(mode)`를 exhaustive로
작성한 consumer는 재컴파일 시 `LEASE` branch를 추가해야 하며, 기존 compiled
consumer가 새 `LEASE` 값을 전달받는 동작은 보장하지 않는다. 기본값과 기존 enum
serialization name은 그대로 유지한다.

| `route-guard.enabled` | `authority-mode` | 동작 |
|---:|---|---|
| `false` | any | 기존 outer gate. 어떤 elector도 acquire하지 않는다. |
| `true` | `STATE` | 기존 passive state guard. 변경 없음. |
| `true` | `LEASE` | 선택된 bean의 실행 모델별 lease capability로 request lease를 소유한다. |

`LEASE` mode는 기존 `route-guard.elector-bean` 선택 규칙을 그대로 사용한다.
bean 이름 없음, 이름 오류, 복수 후보는 기존 elector selector error code를
그대로 사용한다. 정확히 하나의 bean이 선택된 뒤 sync capability view가 없을 때만
`LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED`, suspend capability view가 없을 때는
`LEADER_ROUTE_ELECTOR_SUSPEND_LEASE_UNSUPPORTED`로 startup을 중지한다. `CUSTOM` authority bean과
`LEASE`를 섞지 않으며, `STATE`의 audit-state capability 검사는 `LEASE`에
적용하지 않는다. `LEASE`는 passive leader state를 읽어 route를 허용하지 않는다.

`LEASE` mode에서 `route-guard.redirect.enabled=true`는
`LEADER_ROUTE_LEASE_REDIRECT_INCOMPATIBLE` configuration error로 fail-fast한다. lease
contention은 `NotLeader` state와 같은 public leader 위치를 증명하지 않으므로
redirect를 자동으로 연결하지 않는다. redirect가 필요한 별도 passive route는
`STATE` 또는 `CUSTOM` factory에 등록한다.

`LEADER_ROUTE_LEASE_REDIRECT_INCOMPATIBLE`와 `AUTHORITY_MIXED`를 포함한 lease/redirect/
mixed semantic validation과 LEASE 전용 bean 생성은
`route-guard.enabled=true && authority-mode=LEASE`일 때만 실행한다. `enabled=false` 또는
`STATE`에서는 lease/redirect/mixed semantic validation과 LEASE 전용 bean 생성을
수행하지 않으며, 기존 outer gate와 passive state rollback을 그대로 유지한다.

선택된 `LeaderLeaseAcquirer.configuredOptions`가 wait/lease/minimum lease/
auto-extension/DB time의 단일 source of truth다. startup factory는 elector 선택 직후
`configuredOptions.copy()`를 한 번 호출해 불변 `configuredOptionsBaseline`을
캡처하고, 이후 validation, route slot 생성, lifecycle, watchdog, cleanup은 모두 이
기준 데이터만 읽는다. delegate가 mutable options를 노출하거나 기준 데이터 생성
이후 값을 변경하면 capability adapter가 startup에서 거부하며, dynamic refresh로 이 값을
바꾸는 동작은 제공하지 않는다. route 설정은 TTL을 덮어쓰지 않고
blocking worker, cleanup, active lease, watchdog의 운영 상한만 정의한다. 기본값도
명시적인 bounded admission으로 제공한다.
각 acquire attempt와 공개 handle은 이 동일한 `configuredOptionsBaseline` 값만
private implementation state로 캡처하며, 이후 delegate의 live `configuredOptions`
참조를 보유하지 않는다.

```text
LeaderRouteLeaseProperties
  maxBlockingWaitTime = 5s
  maxConcurrentAcquires = 256
  maxConcurrentCleanups = 256
  maxAcquireQueueDepth = 1024
  maxCleanupQueueDepth = 1024
  maxMvcBlockingAcquires = 32
  maxActiveLeases = 10_000
  maxResidualLeases = 1_024
  maxWatchdogInFlight = 256
  maxLeaseLifetime = 10m
  minimumAutoExtendLeaseTime = 100ms
  maxExpectedExtensionLatency = 50ms
  drainTimeout = 30s
```

`LeaderRouteLeaseProperties`의 public FQN은
`io.bluetape4k.leader.spring.properties.LeaderRouteLeaseProperties`다. 이 타입은
`java.io.Serializable` `data class`이며 모든 property가 `java.time.Duration` 또는
`Int`이고 기본값을 갖는다. `serialVersionUID=1L`, public no-argument constructor,
기본 constructor, `copy`/`copy$default`를 제공하고, 모든 property의 명시적인
기본값으로 Spring Boot constructor binding과 Java caller를 모두 지원한다. 값은
binding 이후 불변 기준 데이터로만 사용하며, null/legacy stream 값은
`readResolve`에서 위 기본값으로 정규화한다. 각 property의 정확한 JVM type과
descriptor는 아래 호환성 절에서 고정한다.

metadata와 binding contract는 다음 기본값/범위를 고정한다.

| key suffix | type | default | range/관계 |
|---|---|---:|---|
| `max-blocking-wait-time` | `java.time.Duration` | `5s` | `> 0`, `<= 5m` |
| `max-concurrent-acquires` | `Int` | `256` | `1..4096` |
| `max-concurrent-cleanups` | `Int` | `256` | `1..4096` |
| `max-acquire-queue-depth` | `Int` | `1024` | `1..65536`, enqueue 전 reserve |
| `max-cleanup-queue-depth` | `Int` | `1024` | `1..65536`, enqueue 전 reserve |
| `max-mvc-blocking-acquires` | `Int` | `32` | `1..max-concurrent-acquires` |
| `max-active-leases` | `Int` | `10000` | `1..100000` |
| `max-residual-leases` | `Int` | `1024` | `1..max-active-leases`, process residual registry cap |
| `max-watchdog-in-flight` | `Int` | `256` | `1..65536` |
| `max-lease-lifetime` | `java.time.Duration` | `10m` | `> 0`, `<= 24h` |
| `minimum-auto-extend-lease-time` | `java.time.Duration` | `100ms` | `> 0`, `<= max-lease-lifetime` |
| `max-expected-extension-latency` | `java.time.Duration` | `50ms` | `> 0`, `<= 1m`, lease time의 1/3 이하 |
| `drain-timeout` | `java.time.Duration` | `30s` | `> 0`, `<= 10m` |

startup validation은 `configuredOptionsBaseline.waitTime <= maxBlockingWaitTime`,
`configuredOptionsBaseline.leaseTime <= maxLeaseLifetime`, `autoExtend=false` 또는
`configuredOptionsBaseline.leaseTime >= max(minimumAutoExtendLeaseTime,
3 * maxExpectedExtensionLatency)`를 보장한다. 모든 값은 양수/상한/관계 조건을
검증하고, queue depth는 `1..65536` 범위의 양수여야 하며 acquire/cleanup enqueue 전에 각각의
`tryReserve`가 성공해야 한다. acquire/cleanup/active lease/residual admission은 semaphore
또는 atomic counter로 bounded하게 거부한다. `max-residual-leases <= max-active-leases`도
검증하며 residual slot을 확보하지 못한 새 acquire는 backend 호출 전에 거부한다.
75ms/90ms처럼 extension latency budget을 충족하지 못하는
짧은 TTL은 startup에서 거부한다. `maxLeaseLifetime`은 elector TTL을 줄이는 옵션이
아니라 너무 긴 request-held lease를 `LEASE` mode에서 허용하지 않는 startup safety
cap이다.

`maxLeaseLifetime`은 startup 검사만으로 끝나지 않는다. holder가
`acquiredAt + maxLeaseLifetime` deadline을 캡처하고 watchdog/route runtime이 그
시각 이후 extension을 중지하고 conditional release를 수행한다. MVC async listener와
WebFlux cleanup은 이 deadline을 timeout 경계로 사용하며, deadline 초과를 response
재작성이나 backend force unlock으로 처리하지 않고 bounded cleanup observation으로
남긴다.

운영 불변식은 `activeWatchdogs <= activeLeases <= effectiveActiveCapacity`,
`watchdogExtensionsInFlight <= maxWatchdogInFlight`,
`mvcBlockingAcquires <= maxMvcBlockingAcquires <= maxConcurrentAcquires`다.
acquire/cleanup queue는 각각 `maxAcquireQueueDepth`/
`maxCleanupQueueDepth`까지의 명시적 slot만 가지며, enqueue 전에 `tryReserve`한다.
queue slot 또는 cleanup reservation이 포화되면 새 lease는 backend 호출 전에
`LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 거부하고, 이미 LIVE인 lease의
extension/release는 reservation된 경로에서 끝까지 실행한다. bounded scheduler의
내부 queue가 무한히 쌓이는 구현은 허용하지 않는다.

configuration binding 자체는 permissive하게 유지한다. nested property의 생성자
또는 `init`에서 mode와 무관한 cross-field exception을 던지지 않으며, 실제
`LeaderRouteLeaseValidator`는 `route-guard.enabled=true`이고
`authority-mode=LEASE`인 경우에만 실행한다. 따라서 invalid lease 값이 있어도
`enabled=false` 또는 `STATE` rollback은 정상 기동하고, `LEASE`에서만
`LEADER_ROUTE_LEASE_CONFIGURATION_INVALID`로 fail-fast한다. validator는
redirect policy bean보다 먼저 실행되고, LEASE에서는 redirect bean 생성을 억제하여
malformed redirect 값도 `LEADER_ROUTE_LEASE_REDIRECT_INCOMPATIBLE`보다 generic
binding error를 먼저 만들지 않는다.

route runtime은 다음 lifecycle을 사용한다.

```text
RUNNING
  -> QUIESCING (new acquire/admission 차단, queued acquire 취소)
  -> DRAINING (active handler/request와 watchdog 종료 대기)
  -> DRAINED (residual=0, release/cleanup 완료)
      -> CLOSED (scheduler/executor dispose)
  -> CLOSED_WITH_LEAKS (residual>0 또는 cleanup deadline 초과; terminal sibling,
      fencing/TTL 보호 후 bounded dispose)
```

`CLOSED_WITH_LEAKS`는 `DRAINING`의 terminal sibling이며 `CLOSED` 이후에는
`CLOSED_WITH_LEAKS`로 전이하지 않는다. residual이 0으로 확정된 경우에만
`DRAINING -> DRAINED -> CLOSED`를 허용하고, residual이 남으면
`DRAINING -> CLOSED_WITH_LEAKS`로 종결한 뒤 bounded scheduler/executor dispose를
수행한다. 두 terminal state 모두 신규 acquire를 받지 않으며, public handle에는
`CLOSED_WITH_LEAKS`를 노출하지 않는다.

Spring context close/redeploy는 기본 `drainTimeout` 안에서 acquire queue 취소,
active request의 deadline/async callback 등록, watchdog 중지, release/cleanup 완료,
마지막으로 scheduler dispose 순서를 보장한다. 각 cleanup은 최초 submission 뒤
최대 두 번의 scheduler 재submission(총 scheduler submission 최대 3회)만 허용하며
backoff는 `0ms`, `100ms`다. 재submission은 backend release call이 시작되기 전의
submission/worker 실패에만 적용하고, 이미 시작한 backend release 호출은
재시도하지 않는다. backend adapter는 cleanup deadline을 transport timeout으로
보장해야 하며, deadline을 넘기면 `LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT`과
`LEADER_ROUTE_LEASE_DRAIN_TIMEOUT`을 함께 기록하고 해당 handle을
`CLOSED_WITH_LEAKS` residual로 분류한다. residual은 fencing/TTL로 새 owner와
격리되며 `DRAINED`로 오인하지 않는다. emergency lane 자체가 포화되거나 실패해도
새 acquire는 계속 차단하고, bounded deadline 안에 residual을 기록한 뒤 context
close를 완료한다. 정상 scheduler는 모든 managed task가 terminal이 된 뒤에만
dispose하며, backend timeout contract를 제공하지 않는 elector는 `LEASE`
capability에서 제외한다. 이미 커밋된 response를 되돌리거나 force unlock하지
않으며, 다음 기동은 기존 fencing token 검증으로 stale handle을 거부한다.

성공한 acquire는 `cleanupReservation`과 residual slot을 함께 보유한다. 두 reservation은
`maxActiveLeases`와 `maxResidualLeases` admission에 각각 포함된다. normal cleanup과
emergency cleanup은 같은 bounded cleanup lane의 공통
`cleanupInFlight <= maxConcurrentCleanups` cap을 사용하고, aggregate queue는
`cleanupQueue <= maxCleanupQueueDepth`로 고정한다. 이미 획득한 handle만 사용할 수
있는 non-droppable emergency reservation은 normal queue가 소비하지 못하도록
분리된 reserved slot으로 관리하며, emergency 작업이 먼저 실행된다. 일반
`cleanupScheduler` 제출이 거부되어도 emergency lane은 공통 in-flight cap 안에서
release를 실행한다. emergency reserved slot 또는 residual slot을 확보할 수 없는
새 요청은 acquire 전에 `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 거부하므로,
획득 후 cleanup을 버리는 경로가 없다. cleanup queue/in-flight 또는 emergency
reservation이 deadline 안에 확보되지 않으면 `ResidualLeaseRegistry` transfer가
`RESIDUAL_TRANSFERRED` terminal이 된다. `ACQUIRING` 취소는 이 reservation을
`handoffReservation`으로 바꾸어 late callback의 conditional release가 끝날 때까지
보존한다.

`ResidualLeaseRegistry`는 process/application scope의 root lifecycle이 소유하며
child Spring context close/redeploy 때 dispose하지 않는다. 각 residual entry는
origin context generation, bounded retention deadline
  `min(acquiredAtMonotonic + maxLeaseLifetime + drainTimeout,
  transferAtMonotonic + drainTimeout)`, fencing/TTL proof와
terminal outcome을 저장한다. conditional release/TTL/fencing 또는 transport
timeout terminal이 확인된 entry만 eviction하고 `in-flight`/`drain.residual`을
감소시킨다. retention deadline에 terminal proof가 없으면 entry를
`QUARANTINED_UNKNOWN`으로 고정하고 `drain.residual.expired{reason=unknown}`을
증가시킨다. 이 상태는 slot을 회수하거나 eviction하지 않으며, operator가 backend
health/fencing/TTL proof를 회복할 때까지 process registry가 보유한다. bounded
retention deadline은 관찰·경보 시점이며 안전한 slot 회수 시점이 아니다. 이후 proof가
확인되면 `QUARANTINED_UNKNOWN -> EVICTED`로 한 번만 전이하고
`drain.residual.expired{reason=quarantined}`와 residual counters를 갱신한다.
`maxResidualLeases`에 도달하면 residual slot을 선점하지 못한 새
acquire와 late-handoff는 backend call 전에 fail-closed하며
`drain.residual.rejected`를 증가시킨다. 이미 residual slot을 선점한 handle의
terminal transfer는 실패하지 않으며, 예기치 않은 registry overflow는 고정 fatal
observation으로 중단한다. 이 cap/retention 계약을 보장할 수 없는
backend는 `LEASE` capability에서 제외한다.

모든 성공한 active handle이 residual slot을 함께 선점하므로 startup에서 파생 용량
`effectiveActiveCapacity = min(maxActiveLeases, maxResidualLeases)`를 계산한다. active
admission은 `activeLeases <= effectiveActiveCapacity`를 적용하고, 이 값은 사용자가
직접 binding하는 property가 아니라 두 설정의 단조 감소 파생값이다. `maxResidualLeases`
를 낮춰 active capacity가 줄어드는 구성은 유효하지만, 기존 active handle을 소급
회수하지 않고 신규 acquire만 fail-closed한다.

운영 관측 계약은 다음 고정 이름과 유한한 tag 집합을 사용한다. 수량 meter의 단위는
count이고 latency timer의 논리 단위는 milliseconds다.

| 이름 | 유형/단위 | 허용 tag |
|---|---|---|
| `bluetape4k.leader.route.lease.active` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.active.capacity` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.acquire.queue` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.acquire.queue.capacity` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.acquire.attempts` | Counter (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.acquire.rejected` | Counter (count) | `framework={mvc,webflux,core}`, `reason={contention,admission,unsupported,shutdown}` |
| `bluetape4k.leader.route.lease.cleanup.queue` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.cleanup.queue.capacity` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.cleanup.inflight` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.cleanup.failed` | Counter (count) | `framework={mvc,webflux,core}`, `reason={ordinary,timeout,stale}` |
| `bluetape4k.leader.route.lease.cleanup.timeout` | Counter (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.drain.residual` | Gauge (count) | `framework={core}` |
| `bluetape4k.leader.route.lease.drain.residual.capacity` | Gauge (count) | `framework={core}` |
| `bluetape4k.leader.route.lease.drain.residual.rejected` | Counter (count) | `framework={core}`, `reason={admission,shutdown}` |
| `bluetape4k.leader.route.lease.drain.residual.expired` | Counter (count) | `framework={core}`, `reason={unknown,quarantined}` |
| `bluetape4k.leader.route.lease.drain.retries` | Counter (count) | `framework={mvc,webflux,core}`, `outcome={scheduled,exhausted}` |
| `bluetape4k.leader.route.lease.watchdog.active` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.watchdog.extensions.inflight` | Gauge (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.watchdog.lag` | Timer (milliseconds) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.ownership.unknown` | Counter (count) | `framework={mvc,webflux,core}` |
| `bluetape4k.leader.route.lease.backend.calls` | Counter (count) | `framework={mvc,webflux,core}`, `phase={acquire,release,extend,watchdog}`, `outcome={success,not-held,error,timeout}` |

`active{framework="core"}`는 process-scoped residual registry와 같은 root lifecycle이
소유하는 MVC/WebFlux active handle의 중복 없는 합계이며,
`active.capacity{framework="core"}`는 그 합계에 적용되는
`effectiveActiveCapacity`다. `framework="mvc"|"webflux"` series는 각 실행 모델의
local active/admission view이므로 core series에 다시 합산하지 않는다. 따라서
process-wide capacity alert와 Actuator `effectiveActiveCapacity`는 core series만
사용한다.

Actuator diagnostics의 `leaderRouteLease` schema는
`runtimeState`, `active`, `effectiveActiveCapacity`, `acquireQueue`, `cleanupQueue`, `rejected`,
`acquireQueueCapacity`, `cleanupQueueCapacity`, `cleanupInFlight`, `acquireAttempts`,
`watchdogActive`, `watchdogExtensionsInFlight`, `watchdogLagMs`, `ownershipUnknown`,
`cleanupFailed`, `cleanupTimeout`, `drainResidual`, `drainResidualCapacity`,
`drainResidualRejected`, `drainResidualExpired`, `drainRetries` 필드로 고정한다. 모든 수량은 count, `watchdogLagMs`는
milliseconds이며 lock/token/leader/backend identity는 포함하지 않는다. 이 진단 데이터는
context startup에서 0으로 초기화하고 context close가 terminal state에 도달하면
마지막 bounded 값을 유지하며, 새 context가 생성될 때만 reset한다. 각 field는 위 meter
하나와 일대일로 매핑한다. `effectiveActiveCapacity`는 위
`active.capacity{framework="core"}` gauge의 단일 파생 configuration field다.
`active.capacity{framework="mvc|webflux"}`는 각 실행 모델의 local admission view이며
Actuator field에 합산하지 않는다. 단 `runtimeState`는 meter가 아닌 단일 route runtime
상태이고, Actuator `active` field도 `active{framework="core"}`만 읽어 MVC/WebFlux
series를 다시 더하지 않는다. 그 밖의 일반 field는 framework별 count를 합산하며 `watchdogLagMs`는 framework별
timer의 bounded 최대값으로 계산한다. `drainResidual*` field는 process-scoped
`ResidualLeaseRegistry`의 `framework=core` 단일 series를 그대로 읽고 child context
reset 대상에서 제외한다. 임의 diagnostics field를 추가하지 않는다.
`runtimeState` 허용 값은 `RUNNING`, `QUIESCING`, `DRAINING`, `DRAINED`, `CLOSED`,
`CLOSED_WITH_LEAKS`뿐이다.

route lease metric에는 `lockName`, `leaderId`, token, backend URI를 tag로 사용하지
않는다. Micrometer가 있으면 위 meter를 등록하고, 없으면 bounded in-memory 진단
카운터와 startup warning만 사용하며 unbounded registry를 새로 만들지 않는다.
기존 `LeaderStartupDiagnostics`와 status registry에는 route lease의 aggregate
상태(active, active capacity, queue, cleanup in-flight, rejection, watchdog lag, cleanup failure,
residual capacity/rejection)만 연결하고 동적
lock identity를 `LeaderElectionStatusRegistry`에 등록하지 않는다. 따라서 동적
route 수가 증가해도 기존 status endpoint의 lock-name 집합과 payload 크기를
늘리지 않는다. Actuator가 있으면 이 aggregate를 읽기 전용 diagnostics contributor로
노출하고, 없으면 동일 값을 low-cardinality metric/log로 확인할 수 있는 runbook을
제공한다.

운영 runbook decision tree는 이 명세와 구현 테스트의 기준으로 먼저 고정한다.
현재 pinned `releaseRef=0.5.0` manual에는 새 API claim을 추가하지 않으며,
`docs/manual/en/guides/observability-and-operations.md#leader-route-lease`와
`docs/manual/ko/guides/observability-and-operations.md#leader-route-lease`에 같은
내용을 반영하는 시점은 `releaseRef=1.0.0` pin 확보 이후로 한정한다. (1) startup
`UNSUPPORTED`/`CONFIGURATION_INVALID`는
baseline/options와 mode 조합을 확인하고 `enabled=false` 또는 `STATE` rollback으로
복구한다. (2) contention/admission 증가는
`sum by (framework,reason) (bluetape4k_leader_route_lease_acquire_rejected)`와
`sum(increase(bluetape4k_leader_route_lease_acquire_rejected{reason=~"contention|admission"}[5m])) / clamp_min(sum(increase(bluetape4k_leader_route_lease_acquire_attempts[5m])), 1) > 0.05`,
`max(bluetape4k_leader_route_lease_acquire_queue / bluetape4k_leader_route_lease_acquire_queue_capacity) > 0.8`와
`bluetape4k_leader_route_lease_active{framework="core"} / bluetape4k_leader_route_lease_active_capacity{framework="core"} > 0.8`를
함께 확인하고 bounded 설정 또는 트래픽을 조정한다. Prometheus scrape interval은
15초로 고정하며, admission alert은 5분 rejection 비율이 5%를 초과하거나 queue가
capacity의 80%를 넘거나 core active-capacity 사용률이 80%를 넘으면 울린다. (3) cleanup/drain timeout은 cleanup timeout, residual, retry counter와
`runtimeState`를 확인하고 backend latency/health와 fencing token/TTL을 검증한다.
(cleanup timeout 또는 drain timeout이 한 번이라도 발생하거나 `UNKNOWN`이 증가하면
즉시 조사하고, `rate(bluetape4k_leader_route_lease_ownership_unknown[5m]) > 0` 또는
`bluetape4k_leader_route_lease_drain_residual{framework="core"} > 0`,
`bluetape4k_leader_route_lease_drain_residual_rejected{framework="core"} > 0`,
또는 `bluetape4k_leader_route_lease_drain_residual_expired{framework="core"} > 0`이면
residual alert로 승격한다. residual capacity는
`bluetape4k_leader_route_lease_drain_residual_capacity{framework="core"}`로
확인하고, 두 번의 15초 scrape 동안 residual이 0보다 크면 동일한 조사를 유지한다.)
(4) residual이 증가하면 새 owner의 fencing 검증과 TTL 만료를 확인하고, force unlock
대신 backend health 회복과 bounded redeploy를 수행한다. 모든 단계에서 raw token,
lock identity, exception message를 query나 조치 로그에 넣지 않는다.

blocking acquire와 release를 event-loop 또는 servlet pool에 무제한으로 쌓지 않도록
Spring auto-configuration이 bounded `acquireScheduler`와 `cleanupScheduler`/
executor를 소유한다. caller가 주입한 scheduler는 blocking-capacity contract를
검증하지 못하면 거부하며, scheduler queue/admission/watchdog saturation은
`LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 fail-closed한다. scheduler는 factory가
소유하고 shutdown lifecycle에서만 dispose한다.

```yaml
bluetape4k:
  leader:
    route-guard:
      enabled: true
      authority-mode: LEASE
      elector-bean: orderLeaderElector
      rejection-status: SERVICE_UNAVAILABLE
      lease:
        max-blocking-wait-time: 5s
        max-concurrent-acquires: 256
        max-concurrent-cleanups: 256
        max-acquire-queue-depth: 1024
        max-cleanup-queue-depth: 1024
        max-mvc-blocking-acquires: 32
        max-active-leases: 10000
        max-residual-leases: 1024
        # 파생값: effectiveActiveCapacity = min(10000, 1024) = 1024 (직접 binding하지 않음)
        max-watchdog-in-flight: 256
        max-lease-lifetime: 10m
        minimum-auto-extend-lease-time: 100ms
        max-expected-extension-latency: 50ms
        drain-timeout: 30s
```

위 예제의 실제 active admission 상한은
`effectiveActiveCapacity = min(max-active-leases, max-residual-leases) = 1024`이며,
`effectiveActiveCapacity`는 binding 대상이 아닌 diagnostics/metric 파생값이다.

route마다 다른 TTL이 필요하면 해당 options로 만든 별도 elector bean을
`elector-bean`으로 선택한다. dynamic refresh나 요청별 arbitrary TTL은 제공하지
않는다. 새 `lease` property가 추가되어도 기존 `LeaderRouteGuardProperties`의
4-argument constructor, 5-argument redirect constructor, `copy`, `copy$default`,
`readResolve` bridge는 기존 인자에 `LeaderRouteLeaseProperties()`를 채워 유지한다.

Spring factory의 public `interceptor(slot)`와 `filter(slot)`는 caller가 전달한
`LeaderSlot.lockName`과 `LeaderSlot.leaderId`를 그대로 capability의
`tryAcquire(slot)`에 전달한다. slot을 받지 않는 auto-configuration convenience
경로만 `LeaderSlot(lockName, configuredOptionsBaseline.nodeId)`를 생성한다. 이 두
경로를 혼용하지 않으며, caller slot의 identity를 baseline node id로 덮어쓰지 않는다.

### 4. MVC request lifecycle

`LeaderMvcRouteGuardFactory`는 `LEASE` mode에서 다음 contract를 사용한다.

```text
preHandle
  -> 먼저 non-blocking bounded MVC waiter admission을
     `maxMvcBlockingAcquires` permit으로 reserve
       -> permit 없음: configured rejection, future join/wait와 handler 미호출
  -> 기존 holder가 없을 때만 shared `acquireAttemptPermit`과 `acquireQueueSlot`을
     각각 `maxConcurrentAcquires`/`maxAcquireQueueDepth`에서 non-blocking reserve
        -> 어느 하나라도 없음: 이미 확보한 shared 자원과 `mvcWaiterPermit` 반환,
           backend 미호출 및 fixed admission rejection
  -> opaque factory holder를 원자적으로 ACQUIRING 상태로 설치
       -> holder install race에서 패배: waiter permit 반환 후 동일 규칙으로 종료
  -> ACQUIRING이면 같은 future에 join/wait, LIVE이면 기존 handle 재사용,
     CLOSED이면 새 holder 설치. 각 blocking waiter는 자신의 permit을 보유한 뒤에만
     future를 기다리며, permit 없이 servlet worker가 blocking handoff를 기다리지 않는다.
  -> 최초 owner는 permit 보유 중 caller slot 또는 slot 없는 경로의 startup 기준
     `LeaderSlot(lockName, configuredOptionsBaseline.nodeId)`를 사용해
     `maxBlockingWaitTime` 이하의 bounded
     `LeaderLeaseAcquirer.tryAcquire(routeSlot)` 호출
       -> null: configured rejection, handler 미호출
       -> exception: cancellation/interruption 외에는 rejection
       -> lease: holder를 `PUBLISHED`로 만들고 pre-existing AsyncContext listener를
          등록한 뒤 성공할 때만 `LIVE` 전환 및 handler 실행
            -> listener 등록 실패: `PUBLISHED -> CLOSING` winner가 private handle의
               conditional release를 시도하고 composite reservation 반환 또는
               `ResidualLeaseRegistry` transfer를 완료한 뒤 `CLOSED`, handler 미호출

afterCompletion / AsyncListener terminal completion (sync, exception, async cancel)
  -> 최초 terminal callback만 holder를 `PUBLISHED/LIVE -> CLOSING`으로 CAS하고
     attribute를 소유한 holder로 고정
  -> bounded cleanup executor에서 deadline-aware
     `LeaseCleanupBoundary.releaseWithin(...)` exactly once
  -> `RELEASED/NOT_HELD`이면 composite reservation 반환 후 `CLOSING -> CLOSED` CAS,
     `RESIDUAL_TRANSFERRED`이면 `ResidualLeaseRegistry` transfer 확인 후
     `CLOSING -> CLOSED` CAS 및 `CLOSED_WITH_LEAKS` residual 관찰, 그 뒤 attribute 제거
```

각 MVC waiter의 `mvcWaiterPermit`은 shared
`acquireAttemptPermit`과 별개의 servlet-worker admission 자원이다. `mvcWaiterPermit`은
해당 waiter의 `ACQUIRING` join/handler completion 또는 rejection에서만 반환한다.
shared future가 terminal이 되기 전에는 그 waiter의 permit을 반환하지 않으므로
servlet worker budget과 acquire handoff 대기가 같은 bounded admission 아래에 있다.
중복 instrumentation은 같은 waiter에 두 번째 `mvcWaiterPermit`을 만들지 않으며,
permit이 없을 때는 기존 `ACQUIRING` future가 있어도 join하지 않고 즉시 configured
rejection을 반환한다.

- request attribute에는 lock name 기반 예측 가능한 문자열을 쓰지 않고 factory별
  opaque key와 private factory-instance marker, immutable `LeaderSlot` fingerprint,
  `ACQUIRING/PUBLISHED/LIVE/CLOSING/CLOSED` holder를 저장한다. marker 또는 slot
  fingerprint가 현재 factory/slot과 다르거나 forged object이면 fail-closed하고
  handler를 호출하지 않는다. 같은 route가 중복 등록되어도 `ACQUIRING` holder에
  join/wait하고, atomic install이 하나의 live handle만 만들며 두 번째
  acquire/watchdog/permit/reservation을 만들지 않는다. 서로 다른 slot은 독립
  handle로 관리한다.
- `HandlerInterceptor`와 `AsyncHandlerInterceptor`를 함께 사용한다.
  `afterConcurrentHandlingStarted`에서 lease를 해제하지 않고, Servlet async
  completion/timeout/error 이후의 `afterCompletion`까지 유지한다. async 시작 시
  `AsyncListener`를 등록하고 `onStartAsync`에서 새 `AsyncContext`에 동일 listener를
  재등록한다. `onTimeout`/`onError`는 non-terminal signal만 기록하고 holder를
  `LIVE`로 유지한다. `onComplete` 또는 `afterCompletion` 중 최초 terminal callback만
  holder를 `CLOSING`으로 전이하고 나머지는 no-op으로 끝낸다. listener 등록과
  `afterCompletion`이 경합해도 같은 CAS로 중복 release를 막는다. handler 호출 전에
  이미 존재하는 `AsyncContext`에 listener를 등록할 수 있는 경우에는 acquire callback이
  반환한 private handle을 먼저 `PUBLISHED`로 두고 등록 성공을 handler 진입의
  전제조건으로 삼는다. 이 등록이 실패하면 `PUBLISHED -> CLOSING` winner가 private
  handle의 conditional release를 시도하고 composite reservation 반환 또는
  `ResidualLeaseRegistry` transfer를 끝낸 뒤 `CLOSING -> CLOSED` rollback을
  수행하고 handler를 호출하지 않는다. acquire
  callback 자체가 끝나기 전의 실패는
  `ACQUIRING -> CANCELLED -> CLOSED`로 처리한다. 일반 handler가
  실행 중 `startAsync()`를 처음 호출하는 경우에는 `afterConcurrentHandlingStarted`가
  최초 등록 지점이며, 이 시점의 등록 실패는 이미 실행된 handler를 소급 중단하지
  않고 `afterCompletion`/bounded deadline fallback으로 cleanup한다. async가 이미
  시작된 뒤 container race로 재등록이 실패해도 같은 fallback만 사용한다. timeout/error 뒤
  async redispatch는 terminal callback 전에는 같은 `LIVE` holder를 재사용하고,
  이미 terminal이면 닫힌 holder를 재사용하지 않고 새 bounded acquire 또는 fixed
  rejection으로 처리한다. fallback completion callback이 전혀 없는 container는
  bounded max-lease-lifetime deadline과 diagnostics로 누수를 감시한다.
- `preHandle`에서 `InterruptedException`이 발생하면 interrupt flag를 복구하고
  예외를 전파한다. `CancellationException`은 그대로 전파하며, fatal `Error`도
  숨기지 않는다. ordinary backend exception만 rejection으로 정규화한다.
- MVC `mvcWaiterPermit`과 새 shared `acquireAttemptPermit`/acquire queue slot은
  servlet worker에서 즉시 `tryReserve`하며 reserve를 위해 추가로 기다리지 않는다.
  shared reserve를 획득한 뒤의 backend acquire는
  servlet worker에서 `maxBlockingWaitTime`까지 bounded하게 대기한다.
  `maxMvcBlockingAcquires` permit과
  `maxAcquireQueueDepth` slot을 모두 즉시 `tryReserve`한 요청만 selected elector의
  `configuredOptionsBaseline.waitTime`까지 대기하고,
  `maxBlockingWaitTime`을 넘는 options는 startup에서 거부한다. contention 부하가
  `maxMvcBlockingAcquires`를 초과하면 즉시 fixed rejection으로 fail-closed한다.
- completion release의 ordinary exception은 이미 결정된 response status/body나
  handler primary exception을 덮어쓰지 않는다. release observation을 남기고
  container lifecycle을 계속한다. cleanup reservation이 있는 handle은 servlet
  worker에서 synchronous fallback을 수행하지 않고 non-droppable emergency lane에서
  release를 완료한다. reservation을 잃은 상태는 내부 contract violation으로
  기록하고 cleanup을 완료할 때까지 context close를 진행하지 않는다.
- `minLeaseTime`이 남아 있으면 cleanup executor가 기존 core semantics에 따라
  대기할 수 있다. 이 지연과 `waitTime`은 선택한 elector options의 비용이며,
  `maxBlockingWaitTime`/`maxLeaseLifetime`을 초과하는 조합은 startup에서 거부한다.

### 5. WebFlux request lifecycle

WebFlux는 acquisition과 cleanup이 event-loop를 점유하지 않도록 Spring이 소유하는
별도의 bounded `acquireScheduler`와 `cleanupScheduler` 경계에서 실행한다. 두
scheduler 모두 admission cap과 rejection policy를 가지며, caller-owned
`evaluationScheduler`를 그대로 blocking 경계로 사용하지 않는다. 기존 factory
constructor에 scheduler 인자가 있으면 compatibility bridge가 acquire 경계로만
검증·매핑하고 cleanup scheduler는 항상 별도로 만든다.

```text
defer(acquire on acquireScheduler, bounded admission)
  -> existing ACQUIRING/LIVE holder: waiter/use reference만 연결하고 shared
     `acquireAttemptPermit`/queue slot은 새로 reserve하지 않음
  -> 새 holder: `tryReserve(maxConcurrentAcquires)`와
     `tryReserve(maxAcquireQueueDepth)`를 순서대로 실행
       -> 어느 하나라도 없음: 확보한 shared 자원 반환, backend 미호출, rejection + setComplete
  -> null: rejection + setComplete
  -> scheduler/admission/ordinary exception: rejection + setComplete
  -> lease:
       Mono.usingWhen(
         resource = fencing-aware holder/lease,
         resourceClosure = chain.filter(exchange),
         asyncComplete = cleanupOn(cleanupScheduler),
         asyncError = cleanupOn(cleanupScheduler),
         asyncCancel = cleanupOn(cleanupScheduler),
       )
```

slot을 받지 않는 auto-configured exchange만 startup 기준 데이터로
`val routeSlot = LeaderSlot(lockName, configuredOptionsBaseline.nodeId)`를 만든다.
public `filter(slot)` 경로는 caller의 `LeaderSlot`을 그대로 사용해 sync
`LeaderLeaseAcquirer.tryAcquire(routeSlot)` 또는 native coroutine 경로의
`SuspendLeaderLeaseAcquirer.tryAcquire(routeSlot)`를 호출한다. lock-name overload는
기본 identity를 얻는 public convenience일 뿐 route가 임의 leader id를 만들기 위한
경로가 아니다.

- exchange attribute에는 factory별 opaque holder를 원자적으로 설치한다. holder에는
  private factory-instance marker와 immutable `LeaderSlot` fingerprint가 포함된다.
  marker/slot 불일치나 forged attribute는 fail-closed하고 chain을 구독하지 않는다.
  같은 holder가 `ACQUIRING`이면 중복 filter는 같은 future에 join/wait하고,
  `LIVE`이면 이를 재사용하며 idempotent cleanup만 연결한다. holder는 subscriber별
  `leaseUse` reference count와 acquire-waiter count를 관리한다. `ACQUIRING` 중
  한 subscriber가 cancel하면 해당 subscriber만 detach하고, 다른 waiter/use가
  남아 있으면 shared acquire future와 handoff reservation을 유지한다. 마지막
  waiter까지 사라질 때만 shared acquire를 취소하고 `CANCELLED -> CLOSED` rollback을
  수행한다. `LIVE` 이후에도 complete/error/cancel은 자신의 `leaseUse`만 terminal로
  만들며 reference count가 0이 된 최초 winner만 holder를 `CLOSING`으로 전이하고
  `LeaseCleanupBoundary.releaseWithin(...)`을 호출한다. 따라서 한 duplicate filter의
  cancellation이 아직 실행 중인 다른 subscriber의 lease를 닫지 않는다. handle
  release 자체는 한 번만 backend에 도달한다.
  `tryAcquireUse`는 holder 상태가 `LIVE`인 동안에만 leaseUse reference를
  원자적으로 증가시킨다. `CLOSING` 또는 `CLOSED`로 CAS된 뒤의 duplicate subscriber는
  기존 use를 재사용하지 않고 새 bounded acquire 또는 fixed rejection으로 수렴한다.
- 성공한 acquire가 보유한 composite cleanup reservation은 `Mono.usingWhen`의 세 cleanup
  publisher에 전달된다. 각 publisher는 먼저 non-droppable emergency lane 또는
  bounded `cleanupScheduler`에서 deadline-aware
  `LeaseCleanupBoundary.releaseWithin(...)`를 실행한다. `RELEASED/NOT_HELD`면
  composite reservation 반환 후에, `RESIDUAL_TRANSFERRED`면 registry transfer 확인 후에만
  `Mono.empty()`를 반환한다. 일반 scheduler 제출 거부를 cleanup 완료로 바꾸지
  않고 deadline까지 회복되지 않으면 `RESIDUAL_TRANSFERRED`로 종결한다.
  ordinary cleanup exception은 fixed `LeaseObservationCode`와
  low-cardinality reason만 관찰한 뒤 release 시도 완료 후 `Mono.empty()`로 변환하여 Reactor의 cleanup error가
  primary handler error/value를 덮지 않게 한다. 각 cleanup publisher는
  `primarySignalPresent`를 함께 전달받아, handler value/error 또는 cancellation이
  이미 있으면 ordinary·fatal cleanup failure를 sanitized suppressed observation으로
  남기고 원래 primary signal/value를 반환한다. primary가 없을 때만 fatal `Error`를
  재전파하며, cancellation primary signal은 항상 보존한다. 이 wrapper가
  `Mono.usingWhen`의 기본 cleanup-error override를 허용하지 않는 경계다.
- subscription이 acquisition callback 시작 전에 취소되면
  `acquireAttemptPermit`/acquire queue slot과 backend acquire, handler subscription
  모두 시작하지 않는다. callback이 취소
  직후 늦게 완료되면 공통 handoff CAS가 `CANCELLED/CLOSED`로 수렴하고
  `doOnDiscard`/late-acquire hook이 생성된 handle을 동일한 handoff reservation으로
  `NonCancellable` emergency cleanup에서 operation deadline 안에 conditional
  release한다. acquire가 완료된 뒤 cancel되면 `usingWhen` cancel cleanup이 자신의
  `leaseUse`만 detach하고, 마지막 use인 경우에만 deadline-aware release를 완료한다.
  shared acquire attempt가 `(acquireAttemptPermit, acquireQueueSlot, cleanupReservation,
  residualSlot)` tuple을 보유한다. 성공 callback은 `ACQUIRING -> PUBLISHED`에서
  cleanup reservation과 residual slot을 private holder로 원자 transfer한 뒤
  `acquireAttemptPermit`/queue slot만 callback terminal에 반환하고, 취소·deadline
  callback은 transfer 없이 네 resource를 callback terminal까지 유지한다. 최초 holder owner의 cancellation은
  waiter reference만 detach한다.
- `maxAcquireQueueDepth`/`maxCleanupQueueDepth` slot, active lease/watchdog
  admission 또는 cleanup reservation이 포화되면 새 요청은
  `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`로 fail-closed한다. watchdog start가
  scheduler rejection으로 no-op이 되는 현재 core helper 대신 route required-start
  경로로 호출하며 조용히 성공으로 간주하지 않는다.
- `chain.filter`는 acquire 성공 시 정확히 한 번만 구독한다. null/exception/
  cancellation에서는 handler를 구독하지 않는다.
- WebFlux가 `suspend` handler를 Reactor chain으로 연결하는 경우에도 resource
  ownership은 chain의 complete/error/cancel 신호가 소유한다. 별도 `runBlocking`
  bridge를 만들지 않으며, native coroutine elector를 사용하는 호출자는
  `SuspendLeaderLeaseAcquirer` contract를 직접 사용한다. 이 경로의 release와
  partial-acquire rollback은 `NonCancellable`에서 실행하되 request/close deadline을
  넘기지 않는다. deadline 초과는 `LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT`으로
  관찰하고 residual fencing/TTL 보호로 종결한다.

### 6. 예외·취소·observability 정책

| 상황 | route 응답/신호 | lease 상태 |
|---|---|---|
| 정상 contention (`tryAcquire == null`) | configured rejection, 빈 body | acquire 없음 |
| acquire ordinary exception | configured rejection, 빈 body | 부분 acquire는 helper가 정리 |
| `CancellationException` | 취소 전파 | 이미 생성된 handle은 cancel cleanup, suspend는 `NonCancellable` |
| `InterruptedException` | interrupt 복구 후 전파 | 부분 acquire는 helper가 정리 |
| fatal `Error` | 숨기지 않고 전파 | cleanup 시도 후 재전파 |
| handler exception | 기존 MVC/WebFlux primary exception | completion cleanup exactly once |
| handler 정상 완료 | 기존 response | completion cleanup exactly once |
| auto-extension `NotHeld` | response 재작성 없음, 기존 event/metric | watchdog 관찰/종료 |
| duplicate instrumentation | 동일 live handle 재사용 | backend acquire/release 1회 |
| admission/scheduler 포화 | `LEADER_ROUTE_LEASE_ADMISSION_REJECTED`, 빈 body | acquire 없음 또는 late handle rollback |
| stale handle fencing 불일치 | fixed low-cardinality observation | 새 owner에는 영향 없음 |
| `maxLeaseLifetime` deadline 초과 | response 재작성 없이 `NotHeld`/cleanup observation | handler는 애플리케이션 신호까지 실행될 수 있음 |

기본 응답에는 `lockName`, `auditLeaderId`, token, backend URI, exception message를
넣지 않는다. route lease 경계의 response, log, metric, listener/event는 각각 동일한
redaction 규칙을 적용한다. raw token, lock name, leader id, request URI,
exception message와 backend identity는 어떤 route-facing surface에도 기록하지
않으며, 내부 audit가 필요한 경우에도 one-way code와 bounded reason만 전달한다.
기존 `LeaderElectionListener`와 기존 listener/log 경로는 legacy action API의
호환성을 위해 raw `lockName`/`leaderId` semantics를 그대로 유지한다. 그러나
`authority-mode=LEASE` route 경로는 이 raw listener 또는 raw log listener를
등록·호출하지 않는다. 대신 `SanitizedRouteLeaseObservationSink`(또는 동등한
route observation adapter)에 `LeaseObservationCode`와 고정 tag/reason만 전달하고,
MVC/WebFlux wrapper와 lifecycle helper는 이 sink만 통해 관찰을 발행한다. 따라서
legacy core/action listener의 raw identity 보존과 route boundary의 redaction은
서로 다른 계약이며, route 경계의 revoked/cleanup 관찰은 sanitized sink에서만
최대 한 번 발행한다. listener/event/metric의 허용 tag는
`framework`, `outcome`, `reason`, `admission`, `cleanup`처럼 저 cardinality 값으로
제한한다. cleanup failure는 fixed observation code만 기록하고 message, URI,
credential-bearing detail은 제거한다. 단, 구현이 기록할 수 있는 값은 고정 allow-list인
`LeaseObservationCode={CONTENTION,ADMISSION_REJECTED,UNSUPPORTED,SHUTDOWN,ORDINARY_FAILURE,TIMEOUT,STALE,BACKEND_ERROR,EXTEND_REJECTED,CLEANUP_TIMEOUT,DRAIN_TIMEOUT}`
뿐이며 exception class/name이나 원본 exception 객체는 public 또는 metric surface에
기록하지 않는다. active lease 수,
acquire/cleanup queue depth, rejection 수, watchdog tick lag, extension in-flight와
backend call count를 low-cardinality metric으로 노출한다. route lease가 state를
읽지 않으므로 `STATE`의 `state()` 조회 비용이나 passive decision을 추가하지 않는다.

`LeaseObservationCode`는 내부에서만 안정적으로 사용하는 bounded enum이고,
공개 route 오류 namespace인 `LEADER_ROUTE_*`와는 별도다. 공개 오류가 필요한 경우
다음의 고정 매핑만 사용한다. `ADMISSION_REJECTED`는
`LEADER_ROUTE_LEASE_ADMISSION_REJECTED`, `UNSUPPORTED`는 실행 모델에 따라
`LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED` 또는
`LEADER_ROUTE_ELECTOR_SUSPEND_LEASE_UNSUPPORTED`, `CLEANUP_TIMEOUT`은
`LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT`, `DRAIN_TIMEOUT`은
`LEADER_ROUTE_LEASE_DRAIN_TIMEOUT`으로 변환한다. 그 밖의 observation code는
sanitized 내부 관찰로만 남고 공개 error code로 승격하지 않는다.

listener/event tag allow-list는 `framework={mvc,webflux,core}`,
`outcome={acquired,skipped,held,not-held,unknown,released,error,timeout}`,
`reason={contention,admission,unsupported,shutdown,ordinary,timeout,stale,backend}`,
`admission={accepted,rejected}`, `cleanup={released,failed,timeout,residual}`와
backend metric 전용 `phase={acquire,release,extend,watchdog}`,
`outcome={success,not-held,error,timeout}`로
고정한다. 동적 enum 이름과 exception class/message는 생성하지 않으며, 표의 meter별
tag allow-list는 위 공통 집합에 더해 허용되는 유한한 예외를 정의한다.
구체적으로 `drain.residual.expired`만 `reason={unknown,quarantined}`를,
`drain.retries`만 `outcome={scheduled,exhausted}`를 사용하며, 그 밖의 meter는
공통 집합을 따른다.
redaction 검증은 response, log, metric, listener/event, Actuator diagnostics를
각각 분리해 수행하고 다섯 surface 모두에서 raw token, lock name, leader id,
backend URI, exception class/name/message가 0건임을 확인한다.

`maxLeaseLifetime` deadline에 도달하면 watchdog은 extension을 중지하고 handle을
`NotHeld`로 닫지만, 이미 실행 중인 MVC/WebFlux handler를 임의로 중단하거나
응답을 다시 쓸 수 있다고 가정하지 않는다. 따라서 `LEASE`는 handler가 보유한
fencing-aware lease의 bounded ownership만 보장하고 request 전체 business side
effect의 mutual exclusion을 보장하지 않는다. 애플리케이션은 deadline 이후의
중복 실행에 안전하도록 idempotency/fencing을 제공해야 하며, 이 제한과
deadline 동시 실행 회귀를 문서와 테스트에 포함한다.

## 대안과 거부 이유

| 대안 | 결정 | 이유 |
|---|---|---|
| Spring에만 `LeaderRouteLeaseAuthority` provider 추가 | 거부 | 각 route가 backend의 acquire/watchdog/release를 다시 구현해 async/suspend/virtual parity와 listener semantics가 분기된다. |
| core `LeaderLeaseAcquirer`와 모든 single-leader backend migration | 채택 | ownership lifecycle을 한 core contract로 고정하고 기존 action API와 route adapter가 같은 handle을 공유한다. |
| worker thread bridge로 기존 `runIfLeader`를 요청 끝까지 붙잡음 | 거부 | request cancellation/async completion이 worker lifetime과 분리되고 thread-bound release, starvation, duplicate action 실행을 해결하지 못한다. |
| `LeaderElector`에 새 abstract method를 직접 추가 | 거부 | custom elector와 기존 wrapper의 source/binary compatibility를 깨뜨린다. capability interface를 별도로 두고 `LEASE`에서만 검사한다. |
| route마다 임의 `LeaderElectionOptions`를 전달 | 거부 | constructor-bound backend options와 route options가 달라져 TTL, DB clock, watchdog semantics가 drift한다. |
| `LEASE` contention을 `STATE` redirect로 변환 | 거부 | lease contention은 public leader 위치를 증명하지 않으며 open redirect/오해 가능한 ownership signal이 된다. |

## 실패 모드와 대응

| 실패 | 관찰 가능한 대응 | 상태 변경 |
|---|---|---|
| elector bean 없음/복수/이름 오류 | 기존 selector error code | 없음 |
| elector가 `LeaderLeaseAcquirer` 미구현 | `LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED` startup failure | 없음 |
| `LEASE`와 custom authority 혼합 | `AUTHORITY_MIXED` startup failure | 없음 |
| `LEASE`와 redirect enabled | `LEADER_ROUTE_LEASE_REDIRECT_INCOMPATIBLE` startup failure | 없음 |
| lease property가 mode safety 조건 위반 | `LEADER_ROUTE_LEASE_CONFIGURATION_INVALID` startup failure | enabled LEASE에서만 validator 실행 |
| options가 route safety cap 초과 | bounded wait/TTL/auto-extend validation startup failure | bean 등록 없음 |
| wait time 내 contention | configured rejection, 빈 body | lease 없음 |
| backend acquire ordinary exception | configured rejection, fixed observation | helper rollback |
| acquire/cleanup/active admission 포화 | `LEADER_ROUTE_LEASE_ADMISSION_REJECTED` | acquire 없음 또는 rollback |
| acquire/cleanup queue depth 포화 | `LEADER_ROUTE_LEASE_ADMISSION_REJECTED` | enqueue 전 slot 반환, backend 호출 없음 |
| MVC async timeout/error/cancel | container `afterCompletion` cleanup | release 1회 |
| WebFlux cancel before/after acquire | queued acquire 미실행 또는 `usingWhen` cleanup | release 0/1회 |
| late-acquired handle after cancel | holder CAS 실패 + conditional cleanup | stale handle은 새 owner에 영향 없음 |
| cross-thread release 불가 backend | capability 미구현 또는 startup rejection | ownership API 미변경 |
| release ordinary exception | primary response/error 유지, sanitized cleanup observation | backend 결과는 실패로 관찰 |
| cleanup backend deadline 초과/hang | `LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT`, residual count | `CLOSED_WITH_LEAKS`, fencing/TTL 보호 |
| lease watchdog loss | 기존 extension observation, response 재작성 없음 | backend ownership은 NotHeld |
| Spring context close/redeploy | `RUNNING -> QUIESCING -> DRAINING -> DRAINED -> CLOSED` 또는 `DRAINING -> CLOSED_WITH_LEAKS`와 drain timeout observation | 신규 acquire 차단, bounded cleanup 관찰 후 scheduler dispose |

## 호환성과 운영

- 기존 `LeaderElector`와 `SuspendLeaderElector` 구현은 새 capability를 구현하지
  않아도 compile할 수 있다. `STATE`와 `CUSTOM` route mode는 새 capability를
  조회하지 않는다. `LeaderLease` 상태 데이터의 FQN/serialVersionUID는
  유지하며 새 public lifecycle API는 `LeaderLeaseHandle` 이름을 사용한다.
- `LeaderRouteAuthorityMode.LEASE` enum 추가는 additive이며, default는 계속
  `STATE`다. 기존 enum serialization name과 binary compatibility를 유지하되,
  exhaustive Kotlin `when`의 재컴파일에는 새 branch가 필요하다는 source
  compatibility 경계를 문서화한다.
- `LeaderRouteGuardProperties`는 기존 package
  `io.bluetape4k.leader.spring.properties`와 기존 property 순서를 유지하고,
  같은 package의 `LeaderRouteLeaseProperties`를 `lease` field로 마지막에
  추가한다. 기존 4/5-property JVM surface와 새 6-property surface를 모두
  명시적인 bridge로 보존한다. 아래 descriptor는 `/` 구분 FQN과 JVM primitive를
  사용한 정확한 문자열이며 생략(`...`)을 허용하지 않는다.

  ```text
  # 기존 public no-arg/default surface
  ()V
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;ILkotlin/jvm/internal/DefaultConstructorMarker;)V

  # 기존 4-property constructor/copy/copy$default
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;)V
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
  copy(ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
  copy$default(Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;ILjava/lang/Object;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;

  # 기존 5-property constructor/copy/copy$default
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;)V
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
  copy(ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
  copy$default(Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;ILjava/lang/Object;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;

  # 새 6-property constructor/copy/copy$default
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;)V
  (ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
  copy(ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
  copy$default(Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;ZLio/bluetape4k/leader/spring/properties/LeaderRouteAuthorityMode;Ljava/lang/String;Lio/bluetape4k/leader/spring/properties/LeaderRouteRejectionStatus;Lio/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties;Lio/bluetape4k/leader/spring/properties/LeaderRouteLeaseProperties;ILjava/lang/Object;)Lio/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties;
  ```

  4-property bridge는 `redirect`와 `lease`를 기본값으로 채우고, 5-property
  bridge는 `lease`만 기본값으로 채운다. 기존 mask bit(`0x001`~`0x010`)는
  유지하고 새 6-property default bit `0x020`만 `lease`에 배정한다. `readResolve`는
  legacy stream에서 누락될 수 있는 `redirect`와 `lease`를 각각
  `LeaderRouteRedirectProperties()`와 `LeaderRouteLeaseProperties()`로 복원하며,
  `serialVersionUID=1L`을 유지한다.
- `LeaderRouteLeaseProperties`의 정확한 public FQN은
  `io.bluetape4k.leader.spring.properties.LeaderRouteLeaseProperties`이며
  `java.io.Serializable` data class다. property 순서와 JVM type은 다음과 같다.
  `maxBlockingWaitTime:Ljava/time/Duration;`,
  `maxConcurrentAcquires:I`, `maxConcurrentCleanups:I`,
  `maxAcquireQueueDepth:I`, `maxCleanupQueueDepth:I`,
  `maxMvcBlockingAcquires:I`, `maxActiveLeases:I`, `maxResidualLeases:I`,
  `maxWatchdogInFlight:I`,
  `maxLeaseLifetime:Ljava/time/Duration;`,
  `minimumAutoExtendLeaseTime:Ljava/time/Duration;`,
  `maxExpectedExtensionLatency:Ljava/time/Duration;`,
  `drainTimeout:Ljava/time/Duration;`.
  full constructor descriptor는
  `(Ljava/time/Duration;IIIIIIIILjava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;Ljava/time/Duration;)V`,
  synthetic default descriptor는 여기에 `ILkotlin/jvm/internal/DefaultConstructorMarker;`
  를 추가한 형태이며, public no-arg descriptor는 `()V`다. `serialVersionUID=1L`과
  `readResolve`를 구현하고 모든 기본값은 위 metadata 표와 일치해야 한다.
- auto-configuration의 기존 MVC/WebFlux bean method descriptor와 factory
  constructor bridge는 보존한다. 새 lease runtime wiring은 별도 bean/type으로
  추가하고 기존 `leaderRouteAuthorityRuntime`의 STATE/CUSTOM descriptor를
  바꾸지 않는다.
- configuration metadata, public KDoc, EN/KO README는 `LEASE` mode의 opt-in,
  elector selection, sync/suspend capability 분리, caller `LeaderSlot` identity,
  options inheritance, bounded lease properties, redirect incompatibility,
  completion/cancellation semantics를 같은 구조로 설명한다. 기존
  `leader-spring-boot/README.md`와 `README.ko.md`의 STATE/CUSTOM passive 설명 중
  "never acquire, extend, or release" 문구는 authority별로 분리해 갱신하고,
  MVC/WebFlux 등록, rejection, cancellation, `maxLeaseLifetime`, rollback 예제를
  양쪽 문서에 추가한다. 기존 factory constructor와 scheduler descriptor는 보존하고,
  새 acquire/cleanup scheduler bean은 별도 type/descriptor로 추가한다.
- configuration metadata에는 `route-guard.lease.max-blocking-wait-time`,
  `max-concurrent-acquires`, `max-concurrent-cleanups`, `max-acquire-queue-depth`,
  `max-cleanup-queue-depth`, `max-mvc-blocking-acquires`,
  `max-active-leases`, `max-residual-leases`, `max-watchdog-in-flight`,
  `max-lease-lifetime`, `minimum-auto-extend-lease-time`,
  `max-expected-extension-latency`, `drain-timeout`의 key/type/default/range를
  모두 기록하고 metadata binding/invalid value/disabled rollback 테스트를 추가한다.
- `docs/manual/**`는 manifest의 pinned `releaseRef=0.5.0`,
  `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`에 고정되어 있으므로
  이번 develop 변경의 새 API claim을 추가하지 않는다. 1.0.0 release pin 확보
  후 manual 반영을 별도 작업으로 추적한다.
- rollout은 (1) `enabled=false` 상태로 호환성 업그레이드, (2) startup capability
  preflight와 baseline/options 검증, (3) 한 backend·한 canary route에서 `LEASE`
  활성화, (4) rejection/cleanup timeout/residual/UNKNOWN 지표와 backend health
  확인, (5) 동일 backend의 route를 단계적으로 확대하는 순서를 따른다. canary에서
  `LEADER_ROUTE_ELECTOR_LEASE_UNSUPPORTED`, `LEADER_ROUTE_LEASE_CONFIGURATION_INVALID`,
  cleanup/drain timeout 또는 residual 증가가 관찰되면 다음 단계로 진행하지 않고
  즉시 `enabled=false` 또는 `authority-mode=STATE`로 rollback한 뒤 재기동/redeploy한다.
  dynamic refresh와 branch deletion은 이 이슈의 범위가 아니다.

## 검증 설계

### Core capability contract

- `tryAcquire` 성공 시 `LeaderLeaseHandle`이 lock name/audit identity를 보존하고
  기존 `LeaderLease` 상태 데이터 API는 변경되지 않는다.
- lock-name overload가 `configuredOptionsBaseline.nodeId`를 audit identity로 사용하고,
  slot overload가 caller의 `slot.leaderId`를 보존하는지 확인한다. tenant wrapper가
  scoped lock name만 바꾸고 leader id를 바꾸지 않는지 검증한다.
- 동일 lock의 경쟁 호출은 정상 `null`/skipped event를 반환하고 exception을
  만들지 않는다.
- `release`와 `close`를 같은 thread, 다른 thread, 여러 번 호출해 backend release와
  revoked event가 정확히 한 번만 실행되는지 검증한다. `ACQUIRING/LIVE/CLOSED`
  holder의 atomic duplicate race와 `extend`/`release` 동시 호출도 stress test한다.
  `ACQUIRING` duplicate join/wait, owner cancellation rollback, late-acquire
  handoff를 permit/reservation 단위까지 검증하고, cancellation 폭주에서도
  handoff reservation이 `effectiveActiveCapacity` 상한 안에서 late release까지 유지되는지,
  `CLOSING` 중 in-flight extend와
  late extend가 정의된 선형화 순서를 따르는지 확인한다.
- backend release ordinary failure에서 scheduler 재시도는 허용하되 backend 호출은
  한 번만 발생하고, TTL/fencing 격리와 sanitized cleanup observation이 남는지
  검증한다.
- expire -> reacquire -> old handle `release`/`extend` 순서에서 fencing token이
  새 owner를 보호하는지 검증한다. lock-name-only backend primitive는 capability
  contract test에서 거부한다.
- Redisson은 동일 owner field의 TTL 만료 후 재사용, old handle의 extend/unlock,
  `minLeaseTime` cleanup 경합을 acquisition별 Lua fencing token으로 격리하는지
  검증한다.
- `minLeaseTime`이 release 전에 적용되고, `autoExtend` watchdog이 성공적으로
  시작·연장·종료되는지 검증한다.
- `ExtendOutcome.NotHeld`, backend exception, interruption, cancellation의
  결과와 listener/event parity를 검증한다.
- `runIfLeader`, async, virtual, suspend action API가 explicit lease lifecycle과
  같은 outcome/event/watchdog semantics를 사용하는지 공통 fixture로 검증한다.
- `LeaderLockHandle` context capture와 새 public `LeaderLeaseHandle`가 token/delegate를
  서로 노출하지 않는지 API/serialization test로 검증하고, 기존 `LeaderLease`
  상태 데이터 serialization을 회귀 검증한다.

### Backend matrix

- local은 deterministic unit으로 full lifecycle을 검증한다.
- Lettuce/Redisson, Exposed JDBC/R2DBC, MongoDB, DynamoDB, etcd, Consul,
  Kubernetes, Hazelcast, ZooKeeper는 기존 backend contract/Testcontainers 또는
  backend integration test에 acquire/close/extend/cross-thread cases를 추가한다.
- `Listening*`, micrometer, tenant-scoped wrapper는 delegated capability를
  확인하고 `configuredOptionsBaseline`, slot identity, fencing token scope를 보존하며
  elected/skipped/revoked metric/event가 중복되지 않는지 검증한다.
- capability 없는 custom delegate를 감싼 wrapper가 capability view로 오인되지
  않고 selector 단계에서 stable unsupported code로 거부되는지, capability 있는
  wrapper는 adapter를 통해 event/watchdog/release를 한 번만 위임하는지 검증한다.
- backend별 unavailable/ownership-loss가 정상 skip 또는 `NotHeld` semantics를
  깨뜨리지 않는지 확인한다. container test가 skip되면 green coverage로 간주하지
  않고 원인과 잔여 위험을 기록한다.

### Spring MVC/WebFlux

- `STATE`/`CUSTOM` 회귀: 기존 state read, custom authority, redirect-disabled
  결과가 그대로 유지된다.
- `LEASE` startup matrix: disabled, unique elector, named elector, missing/
  ambiguous elector, unsupported elector, custom authority 혼합,
  redirect incompatible 조합, malformed redirect, invalid lease property의
  enabled/disabled/STATE rollback 순서를 검증한다. LEASE validator가 redirect
  policy보다 먼저 stable code를 반환하는지 확인한다.
- MVC: contention/exception rejection, success handler once, handler exception,
  async completion/timeout/error/cancel, duplicate interceptor, cross-thread
  `afterCompletion` release를 검증한다. `onStartAsync` 재등록, listener 등록과
  `afterCompletion` race, registration failure rollback, non-terminal timeout/error
  signal과 terminal completion precedence, timeout/error 뒤 async redispatch,
  wrong-factory/wrong-slot/forged attribute를 검증한다.
- WebFlux: acquire scheduler boundary, contention/exception rejection, chain once,
  handler error, queued cancellation, post-acquire cancellation,
  late-acquire discard, `Mono.usingWhen` complete/error/cancel cleanup,
  cleanup error primary-signal preservation, duplicate filter, bounded queue/
  rejection, event-loop non-blocking과 scheduler ownership을 검증한다.
- synchronous exception, Reactor cancellation, suspend handler cancellation이
  각각 원래 신호를 보존하고 lease release가 누락되지 않는지 검증한다. suspend
  release가 `NonCancellable`에서 실행되는지 확인한다.
- response에는 `Location`, leader identity, token, backend exception이 들어가지
  않으며 default status/body가 기존 rejection contract와 일치하는지 확인한다.
- Spring context close/redeploy에서 `RUNNING -> QUIESCING -> DRAINING -> DRAINED ->
  CLOSED` 또는 `DRAINING -> CLOSED_WITH_LEAKS` 순서, queued acquire cancellation, active
  handler/watchdog drain, cleanup 완료 전 scheduler 미종료, bounded backend cleanup
  deadline, monotonic clock jump, hung release residual, initial submission plus two
  resubmission limit, fixed cleanup-timeout/drain-residual/retry metrics를 검증한다.
- 고정 metric 이름/type/unit/tag와 finite cardinality를 검증하고, Micrometer 유무,
  Actuator diagnostics aggregate, 기존 status registry에 dynamic lock identity가
  유입되지 않는지 확인한다.
- autoExtend short-TTL matrix는 75ms와 90ms를 명시적으로 reject하고,
  `max(minimumAutoExtendLeaseTime, 3 * maxExpectedExtensionLatency)`의 허용
  경계값을 accept한다. extension latency를 budget보다 크게 주입했을 때
  startup rejection/`NotHeld` observation을 확인하고, `maxLeaseLifetime` deadline
  이후 watchdog extension이 중단되는지 장기 요청 test로 검증한다.
- `maxAcquireQueueDepth`/`maxCleanupQueueDepth` flood에서 queue와 메모리가
  설정 상한을 넘지 않고 enqueue 전 fixed rejection이 발생하는지, deadline 이후
  handler의 동시 side effect 제한이 문서 계약과 일치하는지 검증한다. wall-clock
  jump가 monotonic cleanup deadline을 흔들지 않는지도 검증한다.

### API·문서·저장소 검증

CI 증거는 `.github/workflows/ci.yml`의 `ci-contract`, `manual-contract`,
`build`, `test-core`, `test-spring-boot`와 변경된 backend의 `test-*` job 및
최종 `ci-status` aggregator를 job 이름으로 기록한다. backend parity 범위가 nightly
대상인 경우 `.github/workflows/nightly-tests.yml`의 `test-core`, `test-spring-boot`와
해당 `test-redis-*`, `test-exposed-*`, `test-mongodb`, `test-leader-dynamodb`,
`test-leader-etcd`, `test-leader-consul`, `test-leader-k8s`, `test-hazelcast`,
`test-zookeeper` job과 `nightly-status` 결과를 별도로 기록한다. path-filter로
skipped된 job은 coverage PASS로 취급하지 않고, 해당 backend의 local/contract
evidence 또는 원인과 잔여 위험을 함께 기록한다.

- `./gradlew :bluetape4k-leader-core:test`
- 모든 변경 backend의 targeted test와
  `./gradlew :bluetape4k-leader-spring-boot:test`
- `./gradlew :bluetape4k-leader-spring-boot:build`
- `./gradlew detekt`
- `./gradlew binaryCompatibilityCheck` 및 repository compatibility helper
- EN/KO README configuration/example parity와 public KDoc
- `route-guard.lease.*` configuration metadata key/type/default/range와 binding,
  invalid value, disabled/STATE rollback metadata tests
- `git diff --check` 및 Korean terminology audit
- explicit `tryAcquire/release` vs `runIfLeader`, 대표 remote backend, MVC/WebFlux
  allow/contention/cancel, autoExtend N=1000 watchdog, event-loop/cleanup queue의
  same-host 상대 benchmark를 실행한다. baseline은 기준 SHA
  `56a33db44e22fb137e205119dd853f153cff3402`, warmup 3회/측정 10회로 고정하고,
  p95/p99와 throughput이 baseline 대비 10%를 초과해 악화되면 실패한다. backend
  logical acquire/release call은 성공 lifecycle당 각각 1회, duplicate
  instrumentation 추가 logical call은 0회, watchdog extension은
  `ceil(activeDuration / renewalPeriod) ± 1`회 이내를 pass 기준으로 고정한다.
  backend retry가 설정된 경우 retry budget을 별도 tag로 기록하고 lifecycle call
  예산과 섞지 않는다. 이 기준은 동일 host의 상대 비교이며 cross-host 절대 SLA로
  해석하지 않는다.
- pinned manual inventory/release validation은 새 API claim 없이 비변경 상태를
  확인한다.

수동 manual contract는 다음 명령을 순서대로 실행하고 각 결과를 DoD에 기록한다.

```bash
python3 scripts/ci/validate_ci_fanout.py --static
./gradlew exportManualModuleInventory --no-daemon --no-configuration-cache
MANUAL_REF="$(ruby -e 'require "yaml"; puts YAML.load_file("docs/manual/manifest.yaml")["releaseRef"]')"
MANUAL_SHA="$(ruby -e 'require "yaml"; puts YAML.load_file("docs/manual/manifest.yaml")["releaseCommit"]')"
ruby scripts/manual/release_inventory.rb "$MANUAL_REF" "$MANUAL_SHA" \
  build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb \
  build/manual/release-module-inventory.json docs/manual/manifest.yaml
ruby scripts/manual/validate_release_manuals.rb "$MANUAL_REF" "$MANUAL_SHA"
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
python3 scripts/ci/validate_manual_contract.py
```

## Acceptance criteria

- [ ] `LeaderLeaseAcquirer`와 `SuspendLeaderLeaseAcquirer`가 additive public API로
      각각 `io.bluetape4k.leader`와 `io.bluetape4k.leader.coroutines`에 추가되고,
      `kotlin.time.Duration`/`java.time.Instant` JVM surface와 normal contention
      `null` 계약이 고정된다.
- [ ] `LeaderLeaseHandle`/`SuspendLeaderLeaseHandle`가 token/delegate를 노출하지 않고,
      idempotent release, cross-thread release, extend, `ownershipStatus()`와
      `isStillHeld()`의 `HELD/NOT_HELD/UNKNOWN` semantics를 제공한다. bounded
      serial queue 포화는 `ExtendOutcome.Rejected`와 fixed observation으로
      ownership과 분리된다.
- [ ] 기존 `LeaderLease` 상태 데이터/data class의 FQN, serialization, listener/event
      surface가 유지된다.
- [ ] sync/suspend/async/virtual elector와 listener/micrometer/tenant wrapper가
      하나의 acquire/watchdog/min-lease/release lifecycle을 공유한다.
- [ ] local, Lettuce, Redisson, Exposed JDBC/R2DBC, MongoDB, DynamoDB, etcd,
      Consul, Kubernetes, Hazelcast, ZooKeeper single-leader backend가 capability
      matrix를 충족한다. group/strategic API는 제외되고 회귀만 검증된다.
- [ ] `LeaderElector`와 기존 route `STATE`/`CUSTOM` binary surface 및
      non-exhaustive source 사용이 깨지지 않는다. `LEASE` enum branch가 필요한
      exhaustive Kotlin `when`의 재컴파일 경계는 문서화되어 있다.
- [ ] `LeaderRouteGuardProperties`의 기존 4/5-argument constructor, generated
      default constructor, 5-argument `copy`/`copy$default` JVM descriptor와
      `readResolve` legacy redirect 복원이 유지되고, 새 lease field는 마지막
      overload와 legacy 기본값으로 추가된다. no-arg, old/new default bitmask와
      4/5/6-property full descriptor는 API 문서에 적힌 exact FQN 문자열과
      serialization test로 고정된다. `LeaderRouteLeaseProperties`의 public FQN,
      `java.time.Duration`/`Int` property type, `Serializable`, no-arg, default,
      `serialVersionUID=1L`, `readResolve`도 검증한다.
- [ ] `authority-mode=LEASE`가 outer `route-guard.enabled` 뒤에서만 활성화되고,
      정확히 하나의 bean을 선택한 뒤 실행 모델별
      `LeaderLeaseAcquirer`/`SuspendLeaderLeaseAcquirer` capability를 요구하며
      unsupported/mixed/redirect 조합을 stable configuration code로 거부한다.
      invalid lease 설정은 `enabled=false`/`STATE` rollback에서 기동을 막지 않는다.
- [ ] Spring `interceptor(slot)`/`filter(slot)`는 caller의 lock name/leader id를
      그대로 전달하고, slot 없는 auto-configuration만 baseline node id를 사용한다.
      sync/suspend capability가 없는 실행 모델은 각각 고정 unsupported code로
      거부되며, sync를 suspend로 cast하거나 `runBlocking`하지 않는다.
- [ ] public sync/suspend `release()`/`close()`의 ordinary backend failure는
      `UNKNOWN` observation 후 정상 반환하고, cancellation/interruption/fatal
      `Error`의 전파·suppressed 우선순위와 route primary signal 보존이 고정된다.
      cleanup failure는 primary signal을 대체하지 않으며, primary가 없을 때만
      fatal `Error`/interruption을 해당 규칙으로 전파하고 ordinary failure는
      정상 반환한다. route surface에는 원본 suppressed exception이 기록되지 않는다.
- [ ] route lease는 selected elector의 startup `configuredOptions.copy()` 기준 데이터를
      사용하고 dynamic/request-specific arbitrary options를 만들지 않는다. bounded
      `LeaderRouteLeaseProperties`가 wait/TTL/admission/watchdog safety와 명시적인
      finite upper bound를 검증한다.
- [ ] MVC `preHandle`/`afterCompletion`과 async completion 경계가 success,
      exception, timeout, cancellation에서 lease를 정확히 한 번 정리한다.
      `AsyncListener` `onStartAsync` 재등록, pre-handler registration failure rollback,
      handler가 `startAsync()`를 호출한 뒤의 registration failure fallback,
      cross-thread cleanup을 포함한다. 모든 terminal 경로는
      `PUBLISHED/LIVE -> CLOSING -> CLOSED` 단일 winner와 cleanup reservation
      반환 또는 `ResidualLeaseRegistry` transfer 순서를 따른다. transfer 뒤에는
      holder/runtime `CLOSED_WITH_LEAKS`와 residual admission counter가 고정된다.
- [ ] MVC blocking acquire는 `maxMvcBlockingAcquires` permit과
      `maxBlockingWaitTime`으로 servlet worker 예산을 넘지 않으며, cleanup은
      reservation된 emergency lane에서 실행된다.
- [ ] WebFlux `Mono.usingWhen`이 complete/error/cancel을 모두 cleanup하고
      queued cancellation에서는 acquire/handler를 시작하지 않는다. dedicated
      acquire/cleanup scheduler, late-acquire rollback, ordinary cleanup error의
      primary-signal 보존을 검증한다. shared holder의 acquire-waiter/`leaseUse`
      reference count를 통해 joiner cancellation이 다른 subscriber의 LIVE lease를
      닫지 않음을 검증한다. cleanup publisher는 primary signal/value가 존재할 때
      fatal cleanup failure도 primary를 대체하지 않고 sanitized suppressed
      observation으로 남기며, primary가 없을 때만 fatal `Error`를 전파한다.
      `RELEASED/NOT_HELD`는 composite `(cleanupReservation, residualSlot)`을 반환하고,
      deadline/error/timeout은 같은 composite를 `RESIDUAL_TRANSFERRED`로 registry
      transfer한 뒤 publisher를 종료한다.
- [ ] `ACQUIRING` 중복 요청은 같은 future에 join/wait하고
      `acquireAttemptPermit`/queue slot/composite reservation을
      shared acquire attempt가 `(acquireAttemptPermit, acquireQueueSlot, cleanupReservation,
      residualSlot)` canonical resource tuple의 owner가 된다. 성공 시
      `ACQUIRING -> PUBLISHED`에서 cleanup reservation과 residual slot을 holder로 원자
      transfer하고 `acquireAttemptPermit`/queue slot을 callback terminal에 정확히 한 번
      반환한다. 새 shared attempt는 backend 호출과 `ACQUIRING` 설치 전에
      `maxConcurrentAcquires`/`maxAcquireQueueDepth`를 `tryReserve`하며, 불가하면
      fixed admission rejection으로 끝난다. cancellation/deadline은 transfer 없이
      late callback terminal까지 네 resource를 유지하며,
      late-acquire와 cancellation은 단일 handoff winner로 수렴한다. holder marker와
      slot fingerprint 불일치는 fail-closed다.
- [ ] contention/acquire failure는 configured rejection status와 빈 body이며,
      handler는 호출되지 않는다. 성공 시 handler/chain은 한 번만 호출된다.
- [ ] ordinary exception은 fail-closed/cleanup observation, cancellation은 신호
      보존, suspend release/rollback은 `NonCancellable`에서 수행하며,
      interruption은 flag 복구, fatal `Error`는 전파된다.
- [ ] auto-extension watchdog이 route completion에서 종료되고 min lease와
      `NotHeld` observation이 기존 core semantics를 유지한다. watchdog admission
      cap과 scheduler rejection은 no-op이 아니라 fail-closed이며
      `activeWatchdogs <= activeLeases <= effectiveActiveCapacity`와
      `watchdogExtensionsInFlight <= maxWatchdogInFlight`를 지킨다.
- [ ] duplicate instrumentation이 동일 request/slot에서 두 번째 backend acquire,
      watchdog, revoked event를 만들지 않는다. atomic holder race도 1회로 수렴한다.
- [ ] stale handle의 release/extend가 만료 후 새 owner에 영향을 주지 않고,
      cross-thread와 concurrent extend/release가 `CLOSING` 선형화 규칙을 따른다.
- [ ] canonical state owner가 holder/request factory
      (`ACQUIRING/CANCELLED/PUBLISHED/LIVE/CLOSING/CLOSED/CLOSED_WITH_LEAKS`),
      handle/lifecycle helper (`LIVE/CLOSING/CLOSED`), runtime/shutdown coordinator
      (`RUNNING/QUIESCING/DRAINING/DRAINED -> CLOSED` 또는
      `DRAINING -> CLOSED_WITH_LEAKS`)로 분리되고, public
      handle에는 `ACQUIRING` 또는 `CLOSED_WITH_LEAKS` 상태가 노출되지 않는다.
- [ ] cleanup deadline 초과로 handle이 `CLOSED`가 되고 holder/runtime이
      `CLOSED_WITH_LEAKS`가 되는 terminal operation은 cleanup reservation을
      `ResidualLeaseRegistry`의 private residual owner로 원자 transfer한다.
      residual owner는 `in-flight` admission counter와 `drain.residual`을 유지하며,
      conditional release/TTL/fencing 또는 transport timeout terminal 확인 전에는
      cleanup reservation과 residual slot을 pool에 반환하지 않는다.
      `LeaseCleanupBoundary`와 MVC/WebFlux
      cleanup publisher는 transfer 확인을 bounded terminal completion으로 삼고,
      확인 뒤에만 registry가 회수한다. residual reservation은 새 acquire에
      재사용하지 않는다.
- [ ] normal/emergency cleanup이 공통 `cleanupInFlight <= maxConcurrentCleanups`
      cap과 `cleanupQueue <= maxCleanupQueueDepth` aggregate bound를 공유하고,
      emergency reserved slot은 normal queue가 소비하지 않는다. emergency 또는
      residual slot 포화는 backend call 전 admission rejection으로 관찰되며,
      context close/redeploy를 가로질러 process-scoped `ResidualLeaseRegistry`가
      `maxResidualLeases`와 bounded retention deadline을 지키고, deadline에
      terminal proof가 없으면 `QUARANTINED_UNKNOWN`으로 고정한다. proof 확인 때만
      eviction하고 `drain.residual`/admission counter를 감소시킨다.
- [ ] ordinary release failure는 backend hidden retry 없이 at-most-once 호출,
      TTL/fencing 격리, sanitized observation으로 처리된다.
- [ ] public no-arg `release()`/`close()`는
      `nowMonotonic + min(30s, configuredOptionsBaseline.leaseTime)` default deadline을
      사용하고, route cleanup은 request/close deadline을 우선하는
      `LeaseCleanupBoundary.releaseWithin(...)`을 사용한다.
- [ ] 모든 backend lifecycle callback이 acquisition token/generation과 monotonic
      deadline을 받고 `acquire/extend/release/isHeld/stopWatchdog` 결과 규칙을
      준수하며, `stopWatchdog`는 `STOPPED/NOT_RUNNING/ERROR/TIMEOUT` 결과와
      bounded stop semantics를 사용한다. MVC/WebFlux/late-acquire/context-close
      cleanup이 `LeaseCleanupBoundary.releaseWithin(...)`로 같은 deadline을
      transport까지 전달한다. Redisson acquire는 owner/token/TTL을 단일 atomic
      Lua `EVAL`로 설정·반환한다.
- [ ] bounded acquire/cleanup/active-lease admission, max lease lifetime,
      minimum auto-extend TTL/extension latency budget 및 scheduler rejection이
      startup/runtime metric과 fixed rejection으로 관찰되고, max lease lifetime
      deadline이 실제 watchdog/cleanup에 적용된다. acquire/cleanup queue capacity,
      acquire attempts, watchdog extensions in-flight, ownership UNKNOWN counter도
      고정 schema로 관찰된다. active admission은
      `activeLeases <= effectiveActiveCapacity`와
      `effectiveActiveCapacity=min(maxActiveLeases,maxResidualLeases)`를 함께 검증한다.
- [ ] acquire/cleanup queue depth가 명시적 상한을 가지며 enqueue 전 reserve와
      `1..65536`의 양수 capacity validation과 flood rejection을 보장한다.
      cleanup deadline 초과는
      `CLOSED_WITH_LEAKS`와 fencing/TTL residual로 관찰되고 `DRAINED`로 보고되지
      않는다.
- [ ] successful lifecycle의 logical backend call count가 acquire 1회/release 1회,
      watchdog tick 허용 범위, duplicate 추가 호출 0회라는 contract와
      baseline 대비 10% benchmark gate를 통과한다.
- [ ] context close/redeploy가 신규 acquire를 먼저 차단하고 active request,
      watchdog, cleanup을 drain한 뒤 scheduler를 dispose하며, timeout이 고정
      metric/log와 잔여 handle 수로 관찰된다. residual은 process-scoped
      `ResidualLeaseRegistry`로 handoff되어 `maxResidualLeases`와 bounded retention을
      지키며 terminal eviction 전에는 scheduler dispose가 registry ownership을
      끊지 않는다.
- [ ] Async timeout/error는 non-terminal signal이고 completion/afterCompletion만
      terminal cleanup을 소유하며, terminal 이후 redispatch는 닫힌 holder를
      재사용하지 않는다. deadline/elapsed 계산은 monotonic clock을 사용한다.
- [ ] cleanup retry는 최초 submission + 최대 두 번의 pre-backend resubmission으로
      고정되고, `cleanup.timeout`, `drain.residual`, `drain.retries` metric과
      `LEADER_ROUTE_LEASE_CLEANUP_TIMEOUT`/`LEADER_ROUTE_LEASE_DRAIN_TIMEOUT`이
      고정된 type/tag로 관찰된다.
- [ ] route lease metric 이름/type/unit/tag와 Actuator diagnostics/status registry
      경계와 field/reset schema가 고정되고, Micrometer 미사용 환경에서도 unbounded
      registry나 raw identity가 생기지 않는다. 모든 meter의 `framework` 값은
      `{mvc,webflux,core}`로 고정된다. 고정 runbook의 `increase` 기반 rejection
      ratio query, active capacity 및 양수 queue capacity/alert threshold, cleanup in-flight cap,
      process-scoped core residual capacity/rejection/expired field,
      residual/timeout 대응과 force-unlock 금지 절차도 검증한다.
- [ ] response/log/metric 기본 surface에 raw token, backend address, leader
      identity, exception class/name, exception message가 노출되지 않고,
      `LEASE` route가 raw `LeaderElectionListener`/log listener를 등록하지 않으며
      `SanitizedRouteLeaseObservationSink` 경계에서만 관찰을 최대 한 번 발행한다.
      `LeaseObservationCode`와 `LEADER_ROUTE_*` 공개 오류 namespace의 고정 매핑 및
      고정 allow-list 외의 동적 reason/tag 금지가 검증된다.
- [ ] configuration metadata, EN/KO README, public KDoc, AOT/module/build/
      detekt/binary compatibility 검증이 통과하고 pinned manual은 새 API claim
      없이 검증된다.
- [ ] rollout은 disabled upgrade -> capability preflight -> canary -> bounded
      metrics/health 확인 -> 단계적 확대 순서를 따르고, unsupported/configuration/
      cleanup/drain/residual 기준을 넘으면 enabled=false 또는 STATE로 rollback한다.
      EN/KO README의 기존 passive 문구가 STATE/CUSTOM과 LEASE를 구분하고
      cancellation/maxLeaseLifetime/rollback 예제를 포함한다.

## 요구사항 추적성

| 요구사항 | 설계 근거 | 구현·검증 증거 |
|---|---|---|
| request 전체 lease ownership | core additive capability, MVC/WebFlux lifecycle | handle lifecycle contract, route integration tests |
| wait/lease/minimum/auto-extension | selected elector options inheritance, common lifecycle | backend fixture와 watchdog/min-lease tests |
| 정상 contention | `tryAcquire == null` contract | local/backend skip tests와 fixed rejection response |
| sync/async/suspend/virtual parity | lifecycle helper와 wrapper delegation | common contract, async/virtual/suspend tests |
| exception/cancellation release | 실패 표와 usingWhen/afterCompletion flow | MVC async, WebFlux cancel, suspend `NonCancellable` tests |
| duplicate instrumentation 방지 | atomic request/exchange holder + delegate-only wrappers | duplicate route/filter/listener/metric race tests |
| cross-thread/stale-handle safety | fencing handle release contract과 backend matrix | cross-thread, expire/reacquire, token-generation tests |
| bounded operation cost | route lease properties와 scheduler admission | queue/rejection, watchdog saturation, same-host benchmark |
| passive guard 보존 | STATE/CUSTOM 분리와 redirect incompatibility | existing route regression, startup matrix |
| public compatibility | additive interfaces, constructor/copy bridges | binary compatibility, serialization/API descriptor tests |
| security/observability | raw metadata 비노출, low-cardinality observation | response/log/metric assertions |

## 설계 DoD

- 사용자가 선택한 option 2의 범위가 core capability, all single-leader backend
  migration, Spring route adapter로 구체화되었다.
- public API, options source of truth, backend support matrix, cross-thread release,
  response/cancellation semantics가 implementation plan과 test list로 추적된다.
- #537 passive guard와 #606 redirect의 경계가 명시되고, `LEASE`와 redirect를
  혼합하지 않는 fail-fast rule이 고정되었다.
- P0/P1 review finding은 0이어야 하며, 구현 전에 integrated spec review artifact를
  남긴다.

## Writer gate

- `SPW-01`: PASS — live #607/#700/#537, current core/route anchors, Spring MVC와
  Reactor official lifecycle source, baseline SHA와 unknowns를 확인했다.
- `SPW-02`: PASS — 문제, 범위, additive API, lifecycle, backend matrix, Spring
  flow, failure/security/compatibility, alternatives, acceptance와 DoD를 포함했다.
- `SPW-03`: PASS — 한국어 technical register를 사용하고 code/API/status/URL/
  identifier는 원문 token으로 보존했다.
- `SPW-04`: PASS — issue 요구사항과 current implementation anchors를 각
  acceptance/traceability 항목에 연결하고 official source URL과 retrieval date를
  기록했다.
- `SPW-05`: PASS — 파일을 read-back하여 code fence, 표, 링크, 범위 제외와
  option 2 decision을 확인했다.

설계 검토 통과 조건: integrated review에서 `P0=0`, `P1=0`이고, 미해결 설계
불확실성은 implementation plan에 owner와 검증 방법이 있어야 다음 단계로
진행한다.
