# Issue #535 OBS-03 audit export adapter 설계

## 상태와 범위

- Issue: #535
- Epic: #699 관측성 진단 및 관리 표면 확장
- Train: OBS-03
- 선행: OBS-01/#533, OBS-02/#559 및 잔여 P2/#726
- 후행: OBS-04/#532
- 기준: `origin/develop` `99245f51456eb8fd79f7eeb6f4233577aa39f8ad`

이 문서는 승인된 OBS-03 설계를 고정한다. v1은 `leader-core`의 framework-neutral
export contract, sanitization/redaction, bounded asynchronous delivery와
`leader-micrometer`의 failure/backpressure metrics, JDK `HttpClient` 기반
HTTP/webhook transport를 포함한다.

JSONL sink와 OpenTelemetry adapter는 후속 이슈로 분리한다. Spring Boot와 Ktor
auto-configuration, durable outbox, transport별 운영 설정도 이번 train의 범위에
넣지 않는다.

## 현재 근거

현재 구현과 baseline은 다음 계약을 제공한다.

- `LeaderHistorySink`와 `SuspendLeaderHistorySink`는 acquired/completed/failed
  history 저장과 retention 삭제를 정의한다.
- `LeaderLockHistoryRecord`는 token을 보유하지만 `toString()`에서 token을 가리고,
  `SafeLeaderHistoryRecorder` 계층은 error message와 metadata를 길이·문자 제한에
  맞게 정제한다.
- `LeaderElectionEventPublisher`는 `Elected`, `Revoked`, `Skipped`를 `Flow`로
  노출하며 callback 예외를 선거 실행으로 전파하지 않는다.
- `SafeLeaderHistoryRecorder`와 `SuspendSafeLeaderHistoryRecorder`는 일반 sink
  예외를 best-effort로 격리하고 `CancellationException`과 `InterruptedException`은
  전파한다.
- `leader-micrometer`에는 history sink failure와 acquire-missing counter 및
  `LeaderMetricTagRule` 기반 redaction/cardinality 규칙이 이미 있다.
- 변경 전 `./gradlew :bluetape4k-leader-core:test
  :bluetape4k-leader-micrometer:test`가 `BUILD SUCCESSFUL`이었다.

따라서 새 exporter는 기존 history 저장 계약을 대체하지 않고 decorator/bridge로
재사용해야 하며, core가 Micrometer나 Spring/Ktor에 의존해서는 안 된다.

## 문제

외부 audit sink로 lifecycle/history event를 전달하는 공통 adapter가 없다. 선거
hot path에서 HTTP 호출을 직접 실행하면 네트워크 지연과 retry가 lock 획득·해제와
결합된다. 반대로 단순히 예외를 삼키면 queue 포화, retry 소진, exporter 종료를
관찰할 수 없다. token, raw lock name, leader identity, 예외 메시지를 외부로
그대로 내보내는 것도 보안·cardinality 경계를 위반한다.

## 목표

1. token을 포함하지 않는 immutable `LeaderAuditExportEvent`와 exporter SPI를
   `leader-core`에 추가한다.
2. history sink와 election event publisher를 exporter에 연결하는 blocking/suspend
   bridge를 제공한다.
3. bounded queue, 즉시 admission 결과, bounded retry/backoff, close/cancel 계약을
   core에서 정의한다.
4. export 실패·retry 소진·queue 포화가 leader election 결과나 caller 예외를
   변경하지 않도록 한다.
5. JDK `java.net.http.HttpClient`의 `sendAsync`를 사용하는 HTTP/webhook delivery를
   제공한다. payload encoding은 주입 가능한 함수로 두어 core에 JSON dependency를
   추가하지 않는다.
6. `leader-micrometer`에서 accepted, dropped, retry, delivery failure를 low-cardinality
   metric으로 관찰한다.
7. redaction, metadata/error size, retry/cancellation, backpressure와 lifecycle을
   deterministic test로 고정한다.

## 비목표

- JSONL file sink
- OpenTelemetry log/event exporter
- exactly-once 또는 durable outbox/transactional delivery
- 분산 순서 보장과 cross-process queue
- Spring Boot/Ktor auto-configuration 및 운영 endpoint
- 새 외부 serialization/HTTP dependency 추가
- 기존 `LeaderHistorySink` 저장 semantics 변경

## 설계 선택지

### 선택 A — bounded asynchronous, transport-neutral exporter (채택)

core가 bounded admission과 delivery lifecycle을 소유하고, history/event bridge는
non-blocking `submit`만 호출한다. HTTP adapter는 one-shot delivery를 제공하고
dispatcher가 retry/backoff와 close를 관리한다. 외부 dependency 없이 election hot
path를 보호하며, JSONL/OpenTelemetry를 후속 transport로 추가할 수 있다.

### 선택 B — 동기 adapter와 호출자별 retry

구현은 작지만 sink 또는 HTTP latency가 election thread에 남는다. queue 포화와
caller cancellation을 transport마다 다시 정의해야 하므로 채택하지 않는다.

### 선택 C — HTTP 전용 별도 모듈

transport 격리는 분명하지만 새 모듈·BOM·CI·dependency surface가 생긴다. 이번
issue의 framework-neutral core contract와 작은 train 범위를 넘어가므로 채택하지
않는다.

## 제안 계약

### 안전한 export event

`LeaderAuditExportEvent`는 sealed interface로 두고 다음 두 변형을 제공한다.

- `History`: `occurredAt`, `lockName`, `kind`, `status`, `nodeId`, `slotId`,
  `durationMs`, `errorType`, `errorMessage`, bounded `attributes`
- `Lifecycle`: `occurredAt`, `lockName`, `outcome(ELECTED|REVOKED|SKIPPED)`,
  optional `leaderId`, `leaseExpiry`, bounded `attributes`

event에는 backend token, `LeaderHistoryKey.token`, raw `LeaderLease` 객체를 넣지
않는다. `lockName`, `nodeId`, `leaderId`, attribute key/value와 error message는
event를 만들기 전에 core redaction policy를 적용한다. 기본 policy는 민감하거나
무제한 cardinality가 될 수 있는 값을 `redacted`로 바꾼다. 정책은 임의 함수를
주입하는 형태가 아니라 `REDACT`, `HASH`, `TRUNCATE`, `RAW` 모드와 검증된 field
allow-list로 표현한다. `HASH`와 `TRUNCATE`는 명시적 opt-in에서만 허용하고,
`RAW`는 사전에 정해진 비민감 enum field에 대해서만 allow-list와 최대 길이 검증을
통과할 때 사용할 수 있다. 따라서 임의 sanitizer 구현이 token, credential, lock 이름,
leader identity, attribute, error message를 우회해 복원할 수 없다. event의
`toString()`과 payload encoder 입력도 이 sanitized 값만 포함한다.

v1 bound 상수는 `MAX_ERROR_MESSAGE_BYTES=4096`, `MAX_ERROR_TYPE_BYTES=128`,
`MAX_TEXT_FIELD_BYTES=256`, `MAX_ATTRIBUTES=32`, `MAX_ATTRIBUTE_KEY_BYTES=128`,
`MAX_ATTRIBUTE_VALUE_BYTES=512`, `MAX_ATTRIBUTES_TOTAL_BYTES=8192`로 고정한다.
모든 byte bound는 UTF-8 기준이며 over-limit 문자열은 code point를 자르지 않는 최대
prefix로 truncate하고 ellipsis를 추가하지 않는다. attribute는 sanitized key의 UTF-8
byte 순으로 정렬한 뒤 key/value 개별 bound와 aggregate bound를 순서대로 적용하며,
한도를 넘는 후속 entry는 drop한다. sanitize 후 key collision은 정렬상 먼저 온 entry만
유지한다. `Raw(maxBytes)`는 양수 byte 값과 비민감 field allow-list를 생성 시 검증하고,
허용되지 않은 field는 `IllegalArgumentException`으로 거부한다.

### Exporter와 admission

public `LeaderAuditExporter`는 다음 경계를 갖는다.

- `submit(event): LeaderAuditSubmitResult`는 queue에 넣는 순간만 동기적으로
  수행한다.
- 결과는 `ACCEPTED`, `DROPPED_QUEUE_FULL`, `DROPPED_CLOSED`로 구분한다.
- `submit`은 delivery 오류를 throw하지 않는다. caller가 결과를 관찰할 수 있지만
  leader election의 반환값·예외에는 영향을 주지 않는다.
- `close()`는 먼저 exporter admission gate를 닫고 queued/retry/in-flight work를
  close-owned cancellation으로 terminalize한다. 이때 발생한 `CANCELLED` observation은
  diagnostics gate가 아직 열려 있을 때 admission한 뒤 diagnostics gate를 닫고 queued
  diagnostics를 drop하며 worker를 interrupt/unpark한다. 네트워크 drain은 기다리지 않는다.
  이미 admission된 observer callback은 완료 또는 abandon될 수 있지만 close는 callback
  종료를 join하지 않으므로 interrupt를 무시하는 callback 때문에 무기한 hang하지 않는다.
  close 이후 새 callback admission은 허용하지 않으며, close 전에 invocation reservation된
  callback만 crossing allowance로 close 반환 뒤 현재 호출을 마칠 수 있다.
- `observe(observer)`는 accepted/drop/retry/terminal-failure/cancel/rejection의 유한한 lifecycle
  outcome을 exporter가 소유하는 diagnostics queue와 이름이 고정된 daemon virtual-thread
  worker를 통해 caller thread 밖에서 best-effort로 전달한다. queue capacity는
  `min(queueCapacity, 1024)`로 파생하고 atomic permit으로 제한한다. `submit`은 queue에
  non-blocking offer만 수행하므로 observer callback과 diagnostics worker가 block되어도
  admission thread를 block하지 않는다. queue가 가득 차면 callback 없이 `observerDrops`만
  증가한다. queue poll, callback reservation, queue drop은 하나의 짧은
  `diagnosticsAdmissionLock`에서 선형화하고, submit-side observation enqueue는 blocking
  `lock()`/monitor/queue wait를 사용하지 않으며 bounded non-blocking `tryLock` 실패 시
  `observerDrops`를 증가시키고 drop한다. exporter `close()`는 같은 lock을 잡아
  diagnostics gate를 닫고 queued callback을 drop한 뒤 worker를 interrupt/unpark하고
  diagnostics queue를 drain하여 더 이상의 callback admission 0을 확인한다. close는 이미
  admission된 callback의 완료를 기다리지 않으므로
  interrupt를 무시하는 observer 때문에 무기한 hang하지 않는다. 이미 admission된 callback은
  close 반환 뒤에도 현재 호출을 마칠 수 있지만 새 callback은 시작하지 않는다. callback
  완료 `finally`도 `diagnosticsAdmissionLock → slot lock` 순서 또는 동등한 원자 경로에서
  `running--`, slot `inFlight--`, diagnostics permit 반환을 exact-once 수행한다. 따라서
  callback 완료와 동시 observation admission이 교차해도 capacity leak이나 double-release가
  없다. observer가
  `Exception`을 던져도 admission, permit, election 결과에는 영향을 주지 않으며 observer
  `Error`는 callback state를 terminalize한 뒤 worker uncaught boundary로 재전파한다.
  event 값·lock 이름·endpoint·error message는 observer payload에 포함하지 않는다.
- `snapshot()`은 queued, in-flight, scheduled-retry, total admitted 수와
  accepted/drop/retry/terminal failure, cancellation, executor/scheduler rejection,
  observer drop, registration drop, diagnostics fatal error와 diagnostics closed 상태도
  누적/표시한 bounded diagnostics를 반환한다. `diagnosticsClosed`는 정상 `close()`와
  observer `Error` 모두에서 diagnostics gate가 닫혔음을 뜻하고,
  `diagnosticsFatalErrors`만 fatal 원인을 구분한다. 스냅숏 값은 lock 이름·endpoint·
  error message를 포함하지 않는다. point-in-time 값은 concurrent update 중 fuzzy할 수
  있고, quiescent 시 `queued + inFlight + scheduledRetries == admitted <= capacity`를
  만족해야 한다.

이 관찰 경계는 public `LeaderAuditExportObserver`와 immutable
`LeaderAuditExportSnapshot`으로 고정한다. snapshot은 explicit 호출 시 O(1) atomic
counter를 읽어 생성하며 submit hot path에서 snapshot 객체를 할당하지 않는다.
Micrometer gauge도 registry poll 시 snapshot을 읽고 admission 중 queue를 순회하지 않는다.
observer enum은 `ACCEPTED`,
`DROPPED_QUEUE_FULL`, `DROPPED_CLOSED`, `RETRY`, `TERMINAL_FAILURE`, `CANCELLED`,
`EXECUTOR_REJECTED`, `SCHEDULER_REJECTED`만 허용하며, snapshot에는 dynamic identifier가
없다. timeout이 소유한 future cancel은 `RETRY` 또는 마지막 attempt의
`TERMINAL_FAILURE`만 만들고 `CANCELLED`를 중복 생성하지 않는다. close-owned cancel은
`CANCELLED`만 만들며 retry/failure를 만들지 않는다.

observer registration은 최대 16개로 제한한다. `diagnosticsAdmissionLock`을 먼저 잡고
각 slot의 `active`와 `inFlight`/invocation reservation을 slot lock에서 관리한다.
observation admission은 gate가 열려 있고 slot `active=true`일 때만 queue permit과
`inFlight++`를 수행한다. worker는 queue poll과 callback 직전 reservation을 같은 gate
lock 아래 수행한다. handle `close()`도 gate lock을 먼저 잡아 slot `active=false`를
선형화한 뒤 registry에서 제거한다. close 전에 reservation된 callback만 crossing
allowance로 남기므로 close 반환 뒤 새 callback invocation은 시작하지 않지만 이미
reservation된 callback은 현재 호출을 마칠 수 있다. 17번째 registration은 no-op handle을 반환하고
`observerRegistrationDrops`를 1 증가시킨다. diagnostics queue 포화는 별도의
`observerDrops`에만 합산하며, queue offer가 실패하면 해당 slot의 `inFlight`와 queue
permit을 즉시 되돌린다. inactive slot item을 dequeue하거나 normal/fatal close에서
queue를 drop할 때도 slot `inFlight`와 diagnostics permit을 같은 gate lock 아래
정확히 한 번 반환한다.
diagnostics worker에서 observer `Error`가 발생하면 diagnostics gate를 CLOSED로
전환하고 queued callback을 drop한 뒤 `diagnosticsFatalErrors`를 증가시키고
`diagnosticsClosed=true`로 표시한 다음 원래 `Error`를 uncaught boundary로 재전파한다.
normal `close()`도 diagnostics gate를 CLOSED로 전환해 `diagnosticsClosed=true`를
표시하지만 fatal counter는 증가시키지 않는다. open 상태에서는 false이며 idempotent
close 이후에도 true를 유지한다. worker를 자동 재시작하지 않으며 이후 `observe()`는
no-op handle을 반환한다.

diagnostics state truth table:

| 상태 | `diagnosticsClosed` | `diagnosticsFatalErrors` |
|---|---:|---:|
| open | `false` | `0` 이상 누적값 |
| 정상 close | `true` | close 전 값 유지 |
| observer `Error` | `true` | 이전 값 + 1 |
| idempotent close | 이전 값 유지 | 이전 값 유지 |

취소 원인별 관찰 순서는 다음 표로 고정한다.

| 취소 주체 | future 결과 | 재시도 | 관찰 outcome | permit |
|---|---|---|---|---|
| timeout winner | `CancellationException(TIMEOUT)` | 남은 attempt가 있으면 `RETRY`, 마지막이면 `TERMINAL_FAILURE` | 위 outcome을 각 1회 | terminal에서 1회 반환 |
| exporter close winner | `CancellationException(CLOSE)` | 없음 | `CANCELLED` 1회 | close에서 1회 반환 |
| caller-owned delivery cancel | `CancellationException(CALLER)` | 없음 | `CANCELLED` 1회 | completion에서 1회 반환 |

dispatcher 옵션은 전체 admitted work(queued, in-flight, scheduled retry)를 제한하는
queue capacity, 최대 동시 delivery, 최대 attempt 수, 양의 유한 `attemptTimeout`,
initial backoff, 최대 backoff, retryable status/exception 분류, clock/scheduler와
executor 주입점으로 구성한다. queue admission은 CAS permit과 non-blocking queue로
선형화하며, `submit`은 lock이나 capacity 대기를 수행하지 않는다. retry는 같은
permit을 유지하고 terminal success/failure/drop/close에서 정확히 한 번 반환한다.
기본값과 hard upper bound는 모두 유한하며, 0/음수·`initialBackoff > maxBackoff`·기간
overflow를 fail-fast로 거부한다. executor/scheduler는 caller 소유이고 exporter가
shutdown하지 않는다. worker-start executor rejection은 그 시점에 worker가 분리한
queued batch만 `EXECUTOR_REJECTED`로 terminalize하고, retry scheduler rejection은
거부된 retry item 하나만 `SCHEDULER_REJECTED`로 terminalize한다. 나머지 queued work는
계속 처리하며 어떤 accepted item도 고착시키지 않고 terminal/drop outcome과 permit
반환으로 끝낸다. close는 worker가 다음 admission을 만들지 않고 실행 중 drain이
종료될 때까지 상태를 CLOSED로 유지한다.

기본 dispatcher 값은 `queueCapacity=1024`, `maxInFlight=8`, `maxAttempts=3`,
`attemptTimeout=5s`, `initialBackoff=100ms`, `maxBackoff=5s`이며 hard upper bound는
각각 `65536`, `queueCapacity`, `16`, `5m`, `1m`, `1m`이다. HTTP payload는 기본
64 KiB, hard upper bound 1 MiB이고 response body는 0 byte로 discard한다.

### Delivery와 HTTP/webhook

dispatcher는 transport-neutral one-shot delivery 함수와 분리한다. HTTP adapter는
주입받은 `HttpClient`, `LeaderAuditTrustedHttpsEndpoint`, headers,
`LeaderAuditPayloadEncoder`를 사용한다. `LeaderAuditTrustedHttpsEndpoint.trusted(URI)`
는 HTTPS·URI syntax만 검사하는 명시적 caller trust wrapper이며, private-network와
DNS/SSRF 정책의 ownership을 caller에게 남긴다.

- encoder는 이미 redacted event를 `ByteArray`로 변환하고 content type을 제공한다.
  payload에는 양의 유한 `maxPayloadBytes` 상한을 적용하며 초과 payload는 request를
  만들기 전에 terminal failure로 끝낸다.
- request는 `POST`로 만들며 2xx만 성공으로 처리한다.
- 408, 429, 5xx와 I/O 실패만 bounded retry 대상이다. 다른 4xx는 즉시 terminal
  failure다.
- `sendAsync` completion은 dispatcher가 재시도·성공·실패로 분류한다. dispatcher의
  `attemptTimeout`과 HTTP request timeout은 모두 양의 유한 값이며, timeout은 retryable
  failure로 한 번만 terminalize한다. timeout 승자는 underlying future를 cancel하고,
  completion 승자는 timeout task를 cancel한다. close가 먼저 이기면 retry/failure
  observer를 만들지 않는다.
- HTTP adapter의 기본 response body handler는 `BodyHandlers.discarding()`이며 response
  body 보존 상한은 0 byte다. 따라서 oversized/chunked body를 메모리에 축적하지 않는다.
  이는 메모리 retention bound일 뿐 네트워크 ingress truncation 계약은 아니다. log에는
  endpoint credential이나 body를 남기지 않는다.
- production target은 `LeaderAuditTrustedHttpsEndpoint.trusted(uri)`로 명시적으로
  감싼 HTTPS-only endpoint만 받고 user-info/query/fragment와 control character를
  거부한다. 이 타입은 caller가 endpoint allow-list와 DNS/SSRF trust를 확인했다는
  explicit ownership boundary이며 library는 hostname을 IP에 pin하거나 DNS rebinding을
  막는다고 주장하지 않는다. private/link-local/ULA/CGNAT와 DNS rebinding은 v1 비목표이고
  운영 문서는 static trusted endpoint 또는 별도 egress proxy를 사용하도록 한다. HTTP
  loopback은 public option이 아니라 `internal` test-only allow-list에서만 허용하며,
  public API에는 임의 scheme allow-list나 insecure-loopback flag가 없다.
  injected `HttpClient`는 `Redirect.NEVER`여야 하며, header map은 immutable
  allow-list로 복사하고 CR/LF·`Host`·`Content-Length`·`Connection` 등 forbidden header를
  거부한다. Authorization 같은 credential header는 전송할 수 있지만 로그·metric·event에는
  절대 복사하지 않는다.
- HTTP adapter는 core에 JSON library를 추가하지 않는다. JSON payload가 필요한
  사용자는 encoder를 제공하며, JSONL/OpenTelemetry는 후속 adapter가 담당한다.
- public `LeaderAuditHttpOptions(maxPayloadBytes)`는 1 MiB hard upper bound와
  기본 64 KiB를 검증하고 scheme allow-list나 insecure-loopback flag를 노출하지 않는다.
  `LeaderAuditHttpPayload.of`는 caller `ByteArray`를 복사하기 전에 1 MiB hard limit을
  검사하며, adapter는 configured lower limit도 request 생성 전에 검사한다.

### 기존 계약 연결

- `ExportingLeaderHistorySink`는 기존 sink를 먼저 호출하고, 반환된 key/record로
  sanitized `History` event를 submit한다. exporter 결과가 `DROPPED_*`여도 기존
  sink 결과를 변경하지 않는다. non-blocking 보장은 exporter admission과 delivery
  대기를 제외하는 의미이며, 기존 history sink/recorder의 동기 latency는 유지한다.
- suspend variant는 같은 admission API를 사용하고 suspend sink 호출의
  `CancellationException` 전파를 유지한다. export submission 자체는 blocking하지
  않는다.
- `LeaderElectionEventPublisher` bridge는 `events`를 구독해 `Lifecycle` event를
  submit하고, 구독 close handle을 반환한다. callback admission과 close는 atomic gate로
  선형화하며 close가 gate를 닫은 뒤 이미 admitted callback이 끝날 때까지 기다린다.
  따라서 close가 반환된 뒤에는 새 submit이 없고, close와 crossing한 event는 gate를
  먼저 획득한 쪽의 결과만 갖는다. callback 또는 exporter failure는 publisher contract
  밖으로 전파하지 않는다.
- exporter lifecycle은 caller가 소유한다. Spring/Ktor가 자동으로 bean·scope를
  만들거나 닫는 동작은 후속 integration issue에서 정의한다.

export delivery는 best-effort다. `ACCEPTED`는 queue admission만 뜻하며 delivered를
보장하지 않는다. `maxInFlight > 1`이면 retry 중복·reordering이 가능하고, process
crash 또는 close는 drain하지 않은 work를 잃을 수 있다. receiver는 event identity를
사용해 idempotency를 제공해야 한다.

### Micrometer 관찰

`MicrometerLeaderAuditExporter`는 core exporter를 소유하는 decorator하고
`(delegate, registry)` exact constructor로 다음 metric을 제공한다. metric의 authoritative
source는 manager가 보유한 active provider의 `delegate.snapshot()` O(1) atomic counter와
detached offset이며 `FunctionCounter`/`Gauge`가 registry polling 시 manager state를 읽는다.
따라서 async diagnostics observer callback이 close 직후 drop되어도 close-owned
cancellation/rejection이 metric에서 사라지지 않는다.
decorator는 registry identity별 내부 manager가 고정된 `Meter.Id` 집합을 한 번만 등록하고
stable meter를 소유한다. manager는 자체 lock/claim token과 `activeProvider` indirection을
사용하며 `FunctionCounter`/`Gauge` callback은 manager state만 캡처하고 delegate를 직접
참조하지 않는다. 첫 acquire에서 foreign ID가 이미 존재하거나 등록 identity를 증명할 수
없으면 constructor는 fail-fast한다. 외부 registration crossing으로 manager-owned identity가
바뀌거나 ambiguous가 되면 `leader.audit.export.meter-ownership-conflict` fixed warning을
한 번 기록하고 state를 compromised로 잠근다. manager는 foreign meter를 절대로 제거하지
않으며, compromised registry는 수동으로 fixed ID를 정리하거나 registry를 교체하기 전까지
새 wrapper를 허용하지 않는다.

전체 stable meter 등록이 성공한 뒤에만 wrapper가 delegate ownership을 확정한다.
constructor 실패 시에는 delegate를 정확히 한 번 닫고 원래 primary 실패를 보존하며,
delegate close/partial-registration cleanup 예외는 primary의 suppressed exception으로
붙인다. primary 실패가 없을 때만 cleanup 예외를 primary로 던진다. 부분 등록은
manager-owned stable meter와 claim state로 남겨 다음 acquire가 누락 ID만 이어서 등록할
수 있으며 foreign ID는 건드리지 않는다. `close()`는 delegate를 idempotently 닫고 마지막
snapshot을 manager offset에 반영한 뒤 `activeProvider`를 clear한다. `registry.remove`를
호출하지 않으므로 lookup/remove 사이 foreign replacement race와 removal residue가 없다.
stable meter는 registry에 남지만 closed delegate를 strong-reference하지 않고 마지막
snapshot/closed 상태를 읽는다. 같은 registry의 새 wrapper는 stable meter identity를
재사용하면서 새 provider를 연결한다. outcome counter는 manager offset을 누적해
replacement에서도 감소하지 않으며 queue/in-flight/closed gauge는 현재 provider 또는
detached closed state를 반영한다. wrapper와 direct delegate 모두 close 후
`DROPPED_CLOSED`가 된다.
non-owning observation은 wrapper가 아니라 public `observe()` handle을 별도로 사용하며
decorator는 내부 observer handle을 소유하지 않는다. stable meter는 close 후에도 manager의
detached snapshot/offset을 읽고 새 wrapper가 acquire되면 새 provider로 전환한다.

- accepted submissions
- queue-full drops
- closed drops
- retry attempts
- terminal delivery failures
- queue depth, in-flight delivery, cancellation/rejection diagnostics
- diagnostics admission saturation as `leader.audit.export.observer.dropped`; this cumulative
  counter mirrors manager offset plus the active snapshot and remains readable after close
  without retaining the closed delegate.
- observer registration cap rejections as `leader.audit.export.observer.registration.dropped`,
  mirroring manager offset plus the active snapshot without a delegate reference after close.
- diagnostics fatal errors as `leader.audit.export.diagnostics.failures` and the CLOSED state
  as `leader.audit.export.diagnostics.closed` (`0|1` gauge), mirroring the corresponding
  snapshot fields.

v1 fixed meter ID 표는 다음과 같으며 총 13개 ID를 등록한다.

| 이름 | 타입 | 정확한 tag | snapshot source |
|---|---|---|---|
| `leader.audit.export.accepted` | `FunctionCounter` | `outcome=accepted` | `accepted` |
| `leader.audit.export.dropped` | `FunctionCounter` | `outcome=queue_full`, `outcome=closed` | `droppedQueueFull`, `droppedClosed` |
| `leader.audit.export.retries` | `FunctionCounter` | `outcome=retry` | `retries` |
| `leader.audit.export.failures` | `FunctionCounter` | `outcome=failure` | `terminalFailures` |
| `leader.audit.export.queue.depth` | `Gauge` | 없음 | `queued` |
| `leader.audit.export.in.flight` | `Gauge` | 없음 | `inFlight` |
| `leader.audit.export.cancelled` | `FunctionCounter` | `outcome=cancelled` | `cancellations` |
| `leader.audit.export.rejections` | `FunctionCounter` | `outcome=rejected` | `executorRejections + schedulerRejections` |
| `leader.audit.export.observer.dropped` | `FunctionCounter` | 없음 | `observerDrops` |
| `leader.audit.export.observer.registration.dropped` | `FunctionCounter` | 없음 | `observerRegistrationDrops` |
| `leader.audit.export.diagnostics.failures` | `FunctionCounter` | 없음 | `diagnosticsFatalErrors` |
| `leader.audit.export.diagnostics.closed` | `Gauge` | 없음 | `diagnosticsClosed` |

v1 aggregate metric은 snapshot에 차원별 값이 없고 decorator constructor에도 source/
transport context가 없으므로 `outcome={accepted,queue_full,closed,retry,failure,cancelled,
rejected}`만 유한 tag로 사용한다. `source`와 `transport` tag는 v1에서 생략하며, mixed
source/transport를 허위로 복제·라벨링하지 않는다. per-dimension metric은 후속 이슈에서
명시적 context 또는 bounded snapshot을 추가할 때만 도입한다. raw lock name, leader ID,
endpoint, error message는 metric tag가 되지 않는다.
기존 `HISTORY_SINK_FAILURES` semantics는 유지하며 새 exporter metric과 합산하지
않는다.

## 실패·취소·수명주기 계약

1. queue가 가득 차면 `DROPPED_QUEUE_FULL`을 반환하고 election은 계속 진행한다.
2. exporter가 닫힌 뒤 submit하면 `DROPPED_CLOSED`를 반환하고 worker/retry가 새
   작업을 만들지 않는다.
3. delivery가 retryable 실패를 반복하면 최대 attempt 후 terminal failure로
   끝내며 caller에게 예외를 던지지 않는다.
4. non-retryable 4xx는 즉시 terminal failure로 처리한다.
5. coroutine scope가 취소되면 bridge subscription은 닫히지만, history recorder가
   sink에서 받은 `CancellationException`을 삼키지 않는다.
6. in-flight HTTP future와 scheduled retry는 close에서 취소하고, late completion은
   counter를 두 번 올리거나 새 retry를 예약하지 않는다. generic delivery도
   cancellation-capable `CompletableFuture`를 반환하므로 close/timeout이 취소를
   전파할 수 있다.
7. exporter callback/encoder가 예외를 내도 기존 election result와 history sink
   result는 보존한다.

## 호환성과 migration

- 기존 public interface와 backend sink 구현은 source/binary 호환을 유지한다.
- exporter는 opt-in 객체이며 기존 recorder/listener 동작은 기본적으로 변하지
  않는다.
- 새 public type에는 Korean KDoc와 JVM ABI fixture를 추가한다.
- serialization format은 v1에서 고정하지 않는다. payload encoder가 wire format을
  소유하므로 JSONL/OpenTelemetry 후속 adapter가 현재 event contract를 재사용할 수
  있다.
- 새 module/dependency/BOM 등록은 발생하지 않는다. `leader-core`와
  `leader-micrometer`만 변경한다.

## Stacked PR 경계

1. `AUD-01` — `feat/epic-obs-03-audit-export-core` / `develop` 기반
   - safe event model, redaction, exporter SPI, bounded dispatcher, history/event
     bridge와 core contract tests
2. `AUD-02` — `feat/epic-obs-03-audit-export-micrometer` / AUD-01 기반
   - Micrometer decorator, low-cardinality counters와 metrics tests
3. `AUD-03` — `feat/epic-obs-03-audit-export` / AUD-02 기반
   - JDK HTTP/webhook delivery, retry/backoff/cancellation tests와 KDoc/README 계약

각 PR은 부모 head를 정확히 고정하고 독립적으로 compile/test 가능한 범위를
가져야 한다. JSONL과 OpenTelemetry는 이 train에 포함하지 않으며 별도 issue로
추적한다.

## 수용 기준

- [ ] core event가 token과 raw sensitive value를 export하지 않는다.
- [ ] blocking/suspend history 및 lifecycle publisher가 같은 exporter admission
      contract를 사용한다.
- [ ] queue 포화·delivery 실패·retry 소진이 election outcome을 변경하지 않는다.
- [ ] retryable/non-retryable HTTP 분류, 최대 attempt, backoff, close/cancel이
      deterministic test로 검증된다.
- [ ] timeout, executor/scheduler rejection, submit-close crossing과 worker 종료가
      deterministic test로 검증된다.
- [ ] URI/header trust boundary와 payload/response byte bound가 negative test로
      검증된다.
- [ ] Micrometer metric은 accepted/drop/retry/failure를 관찰하고 raw/high-cardinality
      값을 tag로 만들지 않는다.
- [ ] 새 dependency와 Spring/Ktor auto-config 없이 `leader-core` 및
      `leader-micrometer` 테스트가 통과한다.
- [ ] 기존 public ABI와 history sink semantics가 유지된다.
- [ ] JSONL/OpenTelemetry가 v1 산출물과 문서에 포함되지 않는다.

## DoD

- 승인된 spec과 implementation plan이 각각 review gate를 통과한다.
- AUD-01~03 stacked PR의 exact head/base, Korean metadata, `## DoD Status`가
  일치한다.
- 대상 Gradle test, `git diff --check`, ABI/contract fixture와 redaction/
  cancellation/backpressure 검증이 fresh PASS다.
- export failure가 election을 중단하지 않는 fake delivery 증거가 있다.
- PR CI와 독립 7-tier review에서 P0/P1이 0이다.
- merge는 현재 exact head에 대한 fresh approval 전에는 실행하지 않는다.
