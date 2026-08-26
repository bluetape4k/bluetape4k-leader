# Epic #701 Ktor lifecycle 및 management surface 설계

> 상태: 설계 승인 후 문서화한 제안. 구현 전 계획 승인 게이트에서 다시 검토한다.

## 1. 목적과 독자

이 문서는 `bluetape4k-leader-ktor`의 Ktor 3.x 사용자와 구현자를 대상으로
Epic #701의 lifecycle, 오류, route guard, event stream 계약을 고정한다.
구현은 기존 `LeaderElectionPlugin`, `leaderScheduled`, management route와
`leader-core`의 `LeaderElectionEventPublisher`를 확장하며, 기존 기본 동작은
깨지지 않아야 한다.

### 근거 자료

| 종류 | 자료 | 확인한 사실 |
|---|---|---|
| 현재 코드 | `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt` | plugin 설정은 `Application.attributes`에 저장되고 lifecycle hook은 로그만 남긴다. |
| 현재 코드 | `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt` | `leaderScheduled`는 Application scope에서 실행되며 예외를 기록하고 다음 cycle을 계속한다. |
| 현재 코드 | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionListener.kt` | event는 hot `Flow`이고 callback subscription은 `AutoCloseable`을 반환한다. |
| 현재 코드 | `leader-core/src/main/kotlin/io/bluetape4k/leader/LockNameValidator.kt` | lock name은 첫 문자가 영숫자이고 이후 영숫자·`_`·`-`·`:`만 허용되며 최대 255자다. |
| 이슈 | Epic #701, #541, #540, #542, #539 | child별 범위와 `KTOR-01 → KTOR-02 → KTOR-03 → KTOR-04` 의존성을 정의한다. |
| 공식 문서 | [Ktor custom plugins](https://ktor.io/docs/server-custom-plugins.html), [application events](https://ktor.io/docs/server-events.html) | `ApplicationStopped`에서 plugin-owned resource를 해제하고 monitor subscription을 정리한다. |
| 공식 문서 | [StatusPages](https://ktor.io/docs/server-status-pages.html), [server serialization](https://ktor.io/docs/server-serialization.html) | 예외 처리는 `StatusPages`, 객체 JSON 직렬화는 `ContentNegotiation`과 converter에 의존한다. |
| 공식 문서 | [Ktor SSE](https://ktor.io/docs/server-server-sent-events.html), [Ktor WebSockets](https://ktor.io/docs/server-websockets.html) | SSE/WebSocket session은 connection scope에서 취소되며 streaming dependency와 cleanup을 명시해야 한다. |
| 공식 API | [createRouteScopedPlugin](https://api.ktor.io/ktor-server-core/io.ktor.server.application/create-route-scoped-plugin.html), [HttpRequestLifecycle](https://api.ktor.io/ktor-server-core/io.ktor.server.http/-http-request-lifecycle.html) | route guard는 public route-scoped plugin API를 사용하고 disconnect cancellation은 별도 설정으로 다룬다. |

현재 test runtime에서 Ktor BOM은 `io.ktor:*:3.5.2`를 해석한다. 새 alias는
버전을 직접 고정하지 않고 기존 Ktor BOM에 맡긴다.

### 확인하지 않은 항목

- #535가 앞으로 `LeaderElectionEvent`의 shape을 변경할지는 확정되지 않았다.
  KTOR-04 구현 직전에 live issue와 exact base head를 다시 확인한다.
- GitHub GraphQL의 Epic `subIssues` 연결은 현재 비어 있다. 이 설계는 이슈
  본문과 댓글에 기록된 train map을 사용하며, formal parent/child metadata를
  별도 변경하지 않는다.
- Ktor optional plugin을 소비자 runtime에 제공하는 방식은 각 child의 계획
  단계에서 resolved dependency와 class-loading 검증을 거친다.

## 2. 범위와 비범위

### 범위

1. plugin-owned lifecycle resource의 idempotent shutdown과 caller-owned
   backend 경계 문서화
2. StatusPages 선택적 통합과 dependency-light JSON 오류 fallback
3. acquire-per-request 없는 route-scoped passive leader guard DSL
4. opt-in SSE/WebSocket event stream, bounded replay, heartbeat, filtering,
   cancellation
5. Ktor test-host 기반 lifecycle·오류·보안·stream 회귀 테스트와 EN/KO 문서

### 비범위

- `leader-core` event 모델의 의미 변경 또는 durable event log 도입
- 무제한 또는 기본 활성화된 route별 lease 획득. 요청별 lease는 KTOR-03의
  명시적 `LEASE` mode에서만 bounded policy와 capability 검증을 거쳐 지원한다.
- Redis/Exposed/MongoDB 등 backend client의 자동 생성·자동 종료
- 인증/인가 provider, proxy discovery, service mesh routing의 구현
- 새로운 Gradle module 또는 CI job 추가. 현재 `leader-ktor` 경로와
  `test-leader-ktor`/Nightly job은 이미 등록되어 있다.

## 3. Train 경계와 의존성

각 child는 이전 child의 exact head를 base로 삼고, 앞 child의 공개 계약을
재사용한다. 한 child 안에서는 변경·테스트·문서·검증을 함께 완료한다.

| Child | branch / base | 설계 책임 | 후속 child가 사용하는 계약 |
|---|---|---|---|
| KTOR-01 / #541 | `feat/epic-ktor-01-lifecycle` / `develop` | `LeaderElectionResourceRegistry`, plugin stop cleanup, scheduler/subscription ownership | plugin registry와 resource registration |
| KTOR-02 / #540 | `feat/epic-ktor-02-errors` / KTOR-01 exact head | `LeaderElectionErrorCode`, stable payload, optional StatusPages adapter, fallback responder | route rejection과 management failure의 공통 오류 계약 |
| KTOR-03 / #542 | `feat/epic-ktor-03-route-guard` / KTOR-02 exact head | route-scoped passive state guard, rejection policy, metadata safe default | non-leader 오류와 lifecycle scope |
| KTOR-04 / #539 | `feat/epic-ktor-04-event-stream` / KTOR-03 exact head | event hub, SSE/WebSocket routes, replay/heartbeat/filter/cancellation | 최종 Ktor management surface |

## 4. 아키텍처

### 4.1 Application lifecycle과 소유권

`LeaderElectionPlugin` 설치 시 애플리케이션별 내부
`LeaderElectionResourceRegistry`를 생성하고 기존 config attribute와 함께
보관한다. registry는 다음 계약을 갖는다.

```kotlin
internal interface LeaderElectionResourceRegistry : AutoCloseable {
    fun register(resource: AutoCloseable): AutoCloseable
    fun register(job: Job): AutoCloseable
    override fun close() // idempotent, reverse registration order
}
```

- 이미 닫힌 registry에 등록하면 전달받은 resource를 즉시 닫고 새 작업을
  애플리케이션에 남기지 않는다.
- `register`와 `close`는 같은 atomic state 경계에서 동작한다. stop 경합에서
  먼저 등록된 resource는 역순으로 정리하고, stop 이후 등록된 resource는
  즉시 닫는다.
- `close()`는 atomic state로 한 번만 본문을 실행하고, 각 close 실패를
  기록한 뒤 나머지 resource를 계속 정리한다. `Job`은 먼저 cancel하고
  bounded timeout 안에서 join을 시도하며 timeout과 실패 수를 shutdown
  관찰 로그에 남긴다.
- `leaderScheduled`가 plugin 설정을 통해 실행될 때 반환한 `Job`을 registry에
  등록한다. plugin이 설치되지 않은 명시적 elector 호출은 기존 Application
  scope cancellation만 사용한다.
- event publisher subscription, event hub collector, auto-extension 또는
  management stream collector는 생성 지점에서 `AutoCloseable` handle을
  registry에 등록한다.
- elector, Redis client, database pool, HTTP client처럼 caller가 생성해
  전달한 객체는 registry가 닫지 않는다. caller가 명시적으로 넘긴 close
  callback만 plugin-owned resource로 취급한다.
- Ktor의 `ApplicationStopped` hook은 registry를 닫고, Application scope와
  connection/session scope의 cancellation은 각각 자신이 만든 child job을
  종료한다. 전역 registry가 connection session을 붙잡지 않도록 한다.

### 4.2 오류 계약

KTOR-02는 machine-readable `LeaderElectionErrorCode`와 HTTP status를
분리한다. 기본 payload는 다음 필드만 사용한다.

```json
{
  "code": "NOT_LEADER",
  "message": "leader state does not allow this request",
  "status": 503
}
```

`lockName`은 진단에 필요할 때만 opt-in으로 추가하며 기본 응답에는 포함하지
않는다. backend exception class, message, endpoint, credential, leader
identity는 응답에 복사하지 않고 application log에 원인과 함께 남긴다.

기본 매핑은 다음과 같다.

| code | 의미 | 기본 status |
|---|---|---:|
| `INVALID_LOCK_NAME` | blank·길이·허용 문자 위반 | 400 |
| `NOT_LEADER` | passive state가 `Occupied`가 아님 | 503 |
| `LEADER_LOCKED` | caller가 선택한 contention/locked 정책 | 423 |
| `BACKEND_UNAVAILABLE` | state/management 조회 backend 실패 | 503 |
| `CONFIGURATION` | plugin 또는 optional integration 설정 오류 | 500 |
| `INTERNAL` | allow-list 밖의 처리 오류 | 500 |

`StatusPages` adapter는 명시적으로 설치할 때만 활성화한다. `StatusPages`가
없거나 `ContentNegotiation` converter가 없으면 route가
`respondText(encodedJson, ContentType.Application.Json, status)`로 같은
필드를 반환한다. override는 typed error context를 받아 allow-list 필드와
허용된 status만 선택하게 하며 임의 body나 원인 노출로 기본 보안 경계를
우회할 수 없다. detached
`leaderScheduled` 예외는 HTTP request 예외가 아니므로 StatusPages로
변환하지 않고 기존 log-and-continue 계약을 유지한다.

표면별 매핑은 다음과 같이 고정한다.

| 표면 | 정상 contention | backend/state 오류 | 원래 예외 |
|---|---|---|---|
| `SuspendLeaderElector.runIfLeader` | `null` 반환 | 기존 `LeaderElectionException` 등으로 전파 | caller가 처리 |
| KTOR-03 `STATE` guard | `NOT_LEADER` + 기본 503 | `BACKEND_UNAVAILABLE` + 503 | log에 cause 보존 |
| KTOR-03 `LEASE` guard | `LEADER_LOCKED` + 기본 423 | `BACKEND_UNAVAILABLE` + 503 | log에 cause 보존 |
| management route | 해당 없음 | `BACKEND_UNAVAILABLE` + 503 | log와 StatusPages context에 보존 |
| `leaderScheduled` background job | 실행하지 않고 다음 cycle | log-and-continue | HTTP 응답으로 변환하지 않음 |

### 4.3 Route-scoped passive guard

KTOR-03은 Ktor public `createRouteScopedPlugin` 기반의 `leaderGuard`와
읽기 쉬운 `leaderOnlyRoute` helper를 제공한다.

- 요청마다 lease를 획득하지 않고 `SuspendLeaderElector.state(lockName)`의
  현재 상태 결과를 한 번 읽는다.
- `STATE` mode는 `supportsAuditLeaderState=true`인 elector 또는 명시적
  `LeaderState` provider만 허용한다. capability가 없거나 기본 `Empty`
  상태만 제공하는 elector는 startup configuration error로 거부해 지원되지
  않는 backend를 정상 non-leader로 오판하지 않는다.
- `LeaderState.isOccupied`이면 downstream route를 실행한다. 그 외에는
  `NOT_LEADER` 오류를 응답하고 downstream을 호출하지 않는다.
- state 조회 자체가 실패하면 fail-closed로 `BACKEND_UNAVAILABLE`을
  반환한다.
- rejection status와 오류 responder는 route별로 override할 수 있지만,
  기본값은 503과 stable JSON이다.
- route guard는 인증·인가·rate limit을 대체하지 않는다. upstream plugin과
  자연스럽게 조합되며, guard가 제공하는 정보는 leader state뿐이다.
- leader identity, backend address, lock name header/body는 기본적으로
  노출하지 않는다. metadata는 명시적 opt-in에서만 allow-list 필드로
  노출한다.
- 이 검사는 요청 처리 전체의 leadership을 보장하지 않는다. 원자적 lease
  보호가 필요한 작업은 기본 `STATE` mode 대신 기존 `@LeaderElection`을
  사용한다. 정말 요청별 lease가 필요한 경우 `LEASE` mode를 명시적으로
  선택하고 `SuspendLeaderLeaseAcquirer` capability와 bounded lease policy를
  제공한다. `LEASE` mode는 `tryAcquire` null을 `LEADER_LOCKED`로 매핑하고
  downstream 실행을 `try/finally`의 idempotent `release()`로 감싼다.

인증과의 순서는 `authenticate { leaderGuard(...) { ... } }`처럼 guard를
인증된 route 안에 중첩하는 계약으로 고정한다. guard는 Ktor route-scoped
`onCall` hook에서 동작하며, 미인증·권한 없음 요청에서는 state provider를
호출하지 않는다. 이 순서와 state 조회 0회 보장은 test-host 회귀 테스트로
검증한다.

### 4.4 Event hub와 streaming

KTOR-04는 `leaderElection is LeaderElectionEventPublisher`인 경우에만
활성화할 수 있다. publisher의 기존 hot `Flow`를 한 번 수집하는
`LeaderEventStreamHub`를 plugin-owned resource로 만든다.

- 이벤트마다 monotonic sequence를 부여하고 고정 크기 ring buffer에 저장한다.
  replay capacity는 `0..1024` 범위를 검증하며 unbounded collection을 허용하지 않는다.
- 새 연결은 lock filter를 검증한 뒤 ring buffer에 저장된 최신 이벤트 목록을 먼저 받고, 이후
  hub의 live flow를 받는다. append·sequence 할당·replay/live handoff는 하나의
  동기화 경계에서 수행해 gap과 duplicate를 방지한다. hub와 연결별 buffer
  모두 bounded 정책을 사용하고 느린 연결은 오래된 이벤트를 버릴 수 있다.
  SSE는 `Last-Event-ID`, WebSocket은 `afterSequence` query를 지원하며 보존
  범위를 벗어난 cursor에는 `replay_gap` control event를 보낸다. replay는
  bounded best-effort이며 durable delivery 또는 exactly-once를 약속하지
  않는다.
- SSE route와 WebSocket route는 기본 비활성화다. 명시적으로 켠 경우에도
  소비자가 각각 `install(SSE)` 또는 `install(WebSockets)`를 제공해야 하며,
  누락 시 startup/configuration error를 명확히 낸다.
- SSE는 `ServerSSESession` scope에서 collector와 heartbeat job을 실행하고
  `finally`에서 close한다. WebSocket은 session scope에서 같은 정리 규칙을
  사용하고 send 실패/peer disconnect를 cancellation으로 처리한다.
- heartbeat는 `event=heartbeat`의 작은 control payload로 보내며, Ktor
  WebSocket ping 설정과 독립적으로 동작한다. heartbeat·replay·filter는
  connection별 설정 상한을 따른다.
- event JSON은 기본적으로 `type`, `sequence`, `lockName`만 사용하고 기존
  `LeaderJsonSupport`의 escaping 규칙을 따른다. `leaderId`와 `leaseExpiry`는
  `eventStreamExposeLeaderMetadata=true`일 때만 추가한다. `LeaderLease`
  전체를 직렬화하지 않는다.
- 기본 event route는 authentication/network boundary가 caller 책임임을
  문서화한다. all-lock stream은 별도 opt-in 없이는 허용하지 않는다.

공개 설정 이름과 경로는 기존 `LeaderElectionPluginConfig` 명명 규칙에 맞춰
다음으로 고정한다.

```text
eventStreamRouteEnabled = false
eventStreamRoutePath = "/management/leaderElection/events"
eventStreamSseEnabled = true
eventStreamWebSocketEnabled = false
eventStreamAllLocksEnabled = false
eventStreamExposeLeaderMetadata = false
eventStreamReplayCapacity = 32
eventStreamHeartbeat = 15s
```

`eventStreamRouteEnabled`가 켜지면 SSE endpoint는
`eventStreamRoutePath`에, WebSocket endpoint는 같은 경로의 `/ws` suffix에
등록한다. 두 endpoint 모두 `lockName` query parameter를 받으며,
`eventStreamAllLocksEnabled=false`일 때는 이 parameter가 필수다. all-lock
stream은 별도 opt-in에서만 허용한다. `eventStreamRouteEnabled=true`인데
두 transport가 모두 꺼져 있으면 configuration error로 시작을 거부한다.
`eventStreamReplayCapacity`는 `0..1024`, `eventStreamHeartbeat`는 유한한
양수로 검증하고, 연결별 send buffer에도 같은 bounded 원칙을 적용한다.

`leaderGuard`의 기본 DSL과 공개 설정은 다음 의미를 고정한다.

```text
Route.leaderGuard(lockName, configure = {}, build = {})
Route.leaderOnlyRoute(lockName, build = {})
LeaderRouteGuardConfig.authorityMode = STATE
LeaderRouteGuardConfig.rejectionStatus = 503
LeaderRouteGuardConfig.exposeMetadata = false
LeaderRouteGuardConfig.leaseMaxDuration = 30s
```

실제 Kotlin overload는 기존 Ktor `Route` DSL의 receiver와 default argument를
보존하되, 위 이름·기본값·passive semantics를 변경하지 않는다. `LEASE`
mode는 `leaseMaxDuration` 안에서만 downstream을 실행하고 초과 시
cancellation 후 release한다.

## 5. 의존성과 호환성

- `ktor-server-status-pages`, `ktor-server-sse`,
  `ktor-server-websockets` alias를 version catalog에 추가한다.
- optional integration source는 별도 adapter 파일/package에 격리하고
  `compileOnly`로 유지한다. 항상 로드되는 plugin/config 클래스는 이
  optional 타입을 직접 참조하지 않는다. 해당 기능을 켜는 소비자는 Ktor
  BOM과 같은 버전의 plugin artifact를 runtime에 제공해야 하며, adapter는
  `pluginOrNull` 확인 후 누락을 configuration error로 보고한다. test source는
  필요한 artifact를 `testImplementation`으로 직접 선언한다.
- optional plugin이 없어도 기존 `LeaderElectionPlugin`, scheduler,
  management route는 로드·실행되어야 한다. 기능 flag가 켜졌는데 plugin이
  없으면 조용한 no-op 대신 명확한 configuration error를 낸다.
- 기존 함수와 config default는 유지한다. 새 API는 additive이며, 기존
  management JSON과 `runIfLeader()` contention-null 계약을 바꾸지 않는다.
- 새 module은 만들지 않는다. `settings.gradle.kts`, BOM constraint, CI,
  Nightly 등록은 변경하지 않는 것을 기본으로 하며, 실제 diff가 이를
  바꾸면 해당 child의 계획과 검증에 명시한다.
- optional artifact를 제거한 classpath smoke를 StatusPages/SSE/WebSockets
  각각 실행해 feature flag가 꺼진 기존 앱의 class loading이 성공하는지
  확인한다.

## 6. 실패·복구·관찰 가능성

- shutdown cleanup은 한 resource의 예외 때문에 다음 resource를 건너뛰지
  않는다. 모든 cleanup failure는 plugin logger에 resource 종류와 함께
  남긴다.
- cancellation은 `CancellationException`을 일반 backend 오류로 바꾸지
  않고 재전파한다. stream disconnect와 application stop 모두에서 같은
  원칙을 지킨다.
- backend/state 오류는 응답에 원인을 노출하지 않고 stable code/status로
  정규화한다. 원래 exception은 log와 테스트 assertion에서 보존한다.
- replay buffer overflow는 오래된 event를 제거하는 bounded 정책으로
  처리한다. persistent replay가 필요하면 별도 durable event issue로
  분리한다.
- 기능 flag를 끄면 route와 collector를 만들지 않는다. rollback은
  descendant child를 먼저 비활성화·revert·retarget한 뒤 ancestor를
  되돌리는 순서를 따른다. 각 child의 기본값은 안전한 비활성 상태로
  유지하고, 이미 열린 후속 PR의 base와 range-diff를 다시 검증한다.

## 7. 수용 기준과 검증

### 기능 수용 기준

- [ ] Application start/stop에서 plugin-owned resource가 정확히 한 번
      정리되고 listener/subscription이 해제된다.
- [ ] caller-owned elector와 backend client는 기본 shutdown에서 닫히지
      않는다.
- [ ] registry의 register/stop 경합, 중복 close, bounded cancel-and-join,
      timeout과 cleanup failure 집계를 검증한다.
- [ ] `leaderScheduled`의 기존 cycle·cancellation·contention 동작이
      유지된다.
- [ ] StatusPages 유무와 ContentNegotiation 유무에서 같은 error code와
      allow-list payload가 나온다.
- [ ] route guard는 passive state만 읽고 non-leader/downstream/error
      경로를 각각 검증하며 unsupported state capability를 startup에서
      거부한다.
- [ ] `LEASE` mode는 capability·bounded policy·idempotent release를
      검증하고, 기본 `STATE` mode는 acquire를 호출하지 않는다.
- [ ] auth/authz/rate-limit/content-negotiation route pipeline과 중첩되며
      미인증 요청에서 state 조회가 없고 metadata는 기본 비활성화다.
- [ ] SSE/WebSocket은 opt-in이며 lock filter, bounded replay, heartbeat,
      disconnect cancellation, invalid lock name, cursor gap과 slow consumer
      정책을 검증한다.
- [ ] event stream이 `LeaderElectionEventPublisher`의 현재 shape을
      재사용하고 기본 payload에 leader identity/lease metadata를 노출하지
      않으며 opt-in에서만 추가한다.

### 검증 명령

각 child에서 다음을 순서대로 실행한다.

```bash
./gradlew :bluetape4k-leader-ktor:test --no-daemon --no-build-cache
./gradlew :bluetape4k-leader-ktor:compileKotlin :bluetape4k-leader-ktor:compileTestKotlin --no-daemon --no-build-cache
./gradlew :bluetape4k-leader-ktor:jar --no-daemon --no-build-cache
./gradlew detekt --no-daemon --no-build-cache
./gradlew exportManualModuleInventory
git diff --check
```

Ktor test-host 단위 테스트는 fake elector/publisher로 deterministic하게
실행하고, 기존 Redisson/Testcontainers 테스트는 별도 순차 실행해 실제
backend lifecycle 회귀를 확인한다. optional artifact를 제거한 runtime
classpath smoke와 StatusPages/SSE/WebSockets 조합별 startup test도 실행한다.
저장소에는 전용 binary-compatibility task가 없으므로 public JVM descriptor는
`jar` 결과와 계획에 정의한 `javap` signature 목록으로 확인한다. 매뉴얼
release inventory/validator와 `export_manifest.rb --check`는 pinned
`releaseRef`/`releaseCommit`을 읽어 실행한다. Kover는 report-only로 사용한다.

## 8. 문서와 공개 surface

- `leader-ktor/README.md`와 `leader-ktor/README.ko.md`의 API·lifecycle·보안
  설명을 같은 범위로 갱신한다.
- `docs/manual/en/modules/bluetape4k-leader-ktor.md`,
  `docs/manual/ko/modules/bluetape4k-leader-ktor.md`와 필요한 Ktor framework
  manual을 release ref 계약에 맞춰 갱신한다.
- 새 public API의 KDoc은 한국어로 작성하고, optional dependency,
  authentication boundary, passive-state limitation, caller-owned resource
  규칙을 예제와 함께 명시한다.
- 각 PR body는 한국어로 작성하고 마지막 section을 `## DoD Status`로 둔다.

## 9. 설계 결정과 대안

| 선택지 | 장점 | 위험 | 결정 |
|---|---|---|---|
| Application scope만 사용 | 코드가 가장 짧다. | subscription close 순서와 exactly-once cleanup을 보장하기 어렵다. | 거부 |
| 전역 singleton supervisor | 구현을 공유하기 쉽다. | 애플리케이션 간 상태·리소스 소유권이 섞인다. | 거부 |
| Application별 registry + session scope | plugin-owned cleanup과 connection cleanup을 분리하고 테스트하기 쉽다. | registry와 handle 구현이 추가된다. | 채택 |
| JSON 객체를 항상 `respond(value)` | converter가 있으면 편리하다. | ContentNegotiation 미설치 앱에서 계약이 깨진다. | 거부 |
| stable JSON `respondText` fallback + optional StatusPages | dependency-light 기본 동작과 override를 함께 보장한다. | 두 경로의 parity 테스트가 필요하다. | 채택 |
| route마다 lease acquire | 강한 실행 보호를 제공한다. | latency와 lock contention을 유발하고 passive guard 요구를 위반한다. | 거부 |

## 10. 설계 DoD

- `SPW-01`: 대상은 Epic #701 Ktor 공개 surface이며 독자는 사용자·구현자다.
  현재 코드, 이슈, 공식 Ktor 문서와 unresolved claim을 위 표에 기록했다.
- `SPW-02`: 경계, API 계약, 실패·복구, 호환성, 수용 기준, 검증 명령,
  rollback, 문서 surface를 포함했다.
- `SPW-03`: 한국어 technical register를 사용하고 API·command·URL·identifier는
  원문 토큰을 보존한다. `KO-01`~`KO-07` 자연스러움 검토를 완료한다.
- `SPW-04`: 현재 source와 live issue의 train order 및 Ktor 3.5.2 dependency
  evidence를 대조했다. #535 event shape와 optional class-loading은 구현
  직전 재검증 대상으로 명시했다.
- `SPW-05`: 이 문서를 렌더링된 Markdown으로 read-back하고 아래 자체 검토
  기록을 남긴다.

### 자체 검토 기록

- 미완성 표기나 빈칸 없음
- train 순서와 각 base가 일치함
- `leader-core` event shape을 변경하지 않는 범위로 고정함
- application-owned, session-owned, caller-owned resource 경계를 분리함
- optional plugin 부재, converter 부재, disconnect cancellation, replay
  overflow, backend 오류를 각각 실패 경로로 기록함
- 수용 기준과 검증 명령이 각 child 범위를 덮음

## 11. Six-lens 검토 결과

설계 초안은 성능, 안정성, 보안, 운영, 개발자/API, 사용자/호출자 관점으로
독립 검토하고 중복을 합쳤다. 다음 P1 항목은 본문에 반영했으며, 미해결 P0/P1은
없다.

| 관점 | 확인한 위험 | 반영한 위치 |
|---|---|---|
| 안정성·API | state capability가 없는 elector를 정상 non-leader로 오판할 수 있음 | §4.3 capability gate와 startup test |
| 안정성·운영 | register/stop 경합과 cancel 완료 경계가 모호함 | §4.1 atomic registry, bounded cancel-and-join |
| 안정성·성능 | replay/live handoff gap과 slow consumer backpressure | §4.4 sequence·cursor·bounded buffer·`replay_gap` |
| 보안 | event 기본 payload의 leader metadata가 비노출 원칙과 충돌함 | §4.4 `eventStreamExposeLeaderMetadata` opt-in |
| 개발자/API | optional Ktor artifact의 class-loading 누수 가능성 | §5 adapter isolation과 classpath smoke |
| 보안·호출자 | 인증보다 guard가 먼저 실행될 수 있음 | §4.3 `authenticate { leaderGuard { ... } }` 순서와 401/403 test |
| API·호출자 | 정상 contention과 HTTP `LEADER_LOCKED` 경계가 불명확함 | §4.2 surface별 mapping matrix와 typed override |
| 운영 | stacked descendant rollback과 공개 API 검증이 부족함 | §6 descendant-first rollback, §7 jar/`javap`/manual 검증 |
