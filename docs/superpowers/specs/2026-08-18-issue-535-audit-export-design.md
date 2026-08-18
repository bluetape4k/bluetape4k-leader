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

### Exporter와 admission

public `LeaderAuditExporter`는 다음 경계를 갖는다.

- `submit(event): LeaderAuditSubmitResult`는 queue에 넣는 순간만 동기적으로
  수행한다.
- 결과는 `ACCEPTED`, `DROPPED_QUEUE_FULL`, `DROPPED_CLOSED`로 구분한다.
- `submit`은 delivery 오류를 throw하지 않는다. caller가 결과를 관찰할 수 있지만
  leader election의 반환값·예외에는 영향을 주지 않는다.
- `close()`는 신규 admission을 차단하고 queued/retry 작업을 취소한다. close는
  네트워크 drain을 기다리지 않으며 idempotent다.
- `observe(observer)`는 accepted/drop/retry/terminal-failure/cancel/rejection의 유한한 lifecycle
  outcome만 전달한다. observer가 예외를 던져도 admission, permit, election 결과에는
  영향을 주지 않으며, event 값·lock 이름·endpoint·error message는 observer payload에
  포함하지 않는다.
- `snapshot()`은 queue depth, in-flight 수, accepted/drop/retry/terminal failure,
  cancellation 및 executor/scheduler rejection을 누적한 bounded diagnostics를
  반환한다. 스냅숏 값은 lock 이름·endpoint·error message를 포함하지 않는다.

dispatcher 옵션은 전체 admitted work(queued, in-flight, scheduled retry)를 제한하는
queue capacity, 최대 동시 delivery, 최대 attempt 수, 양의 유한 `attemptTimeout`,
initial backoff, 최대 backoff, retryable status/exception 분류, clock/scheduler와
executor 주입점으로 구성한다. queue admission은 CAS permit과 non-blocking queue로
선형화하며, `submit`은 lock이나 capacity 대기를 수행하지 않는다. retry는 같은
permit을 유지하고 terminal success/failure/drop/close에서 정확히 한 번 반환한다.
기본값과 hard upper bound는 모두 유한하며, 0/음수·`initialBackoff > maxBackoff`·기간
overflow를 fail-fast로 거부한다. executor/scheduler는 caller 소유이고 exporter가
shutdown하지 않는다. worker/retry schedule rejection은 accepted item을 고착시키지
않고 terminal/drop outcome과 permit 반환으로 끝내며, close는 worker가 다음 admission을
만들지 않고 실행 중 drain이 종료될 때까지 상태를 CLOSED로 유지한다.

기본 dispatcher 값은 `queueCapacity=1024`, `maxInFlight=8`, `maxAttempts=3`,
`attemptTimeout=5s`, `initialBackoff=100ms`, `maxBackoff=5s`이며 hard upper bound는
각각 `65536`, `queueCapacity`, `16`, `5m`, `1m`, `1m`이다. HTTP payload는 기본
64 KiB, hard upper bound 1 MiB이고 response body는 0 byte로 discard한다.

### Delivery와 HTTP/webhook

dispatcher는 transport-neutral one-shot delivery 함수와 분리한다. HTTP adapter는
주입받은 `HttpClient`, target `URI`, headers, `LeaderAuditPayloadEncoder`를
사용한다.

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
  log에는 endpoint credential이나 body를 남기지 않는다.
- target URI는 기본적으로 HTTPS만 허용하고, user-info/query/fragment와 control
  character를 거부한다. HTTP loopback은 명시적 test-only allow-list에서만 허용한다.
  injected `HttpClient`는 `Redirect.NEVER`여야 하며, header map은 immutable
  allow-list로 복사하고 CR/LF·`Host`·`Content-Length`·`Connection` 등 forbidden header를
  거부한다. Authorization 같은 credential header는 전송할 수 있지만 로그·metric·event에는
  절대 복사하지 않는다.
- HTTP adapter는 core에 JSON library를 추가하지 않는다. JSON payload가 필요한
  사용자는 encoder를 제공하며, JSONL/OpenTelemetry는 후속 adapter가 담당한다.

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

`MicrometerLeaderAuditExporter`는 core exporter를 decorator하고 다음 metric을
제공한다.

- accepted submissions
- queue-full drops
- closed drops
- retry attempts
- terminal delivery failures
- queue depth, in-flight delivery, cancellation/rejection diagnostics

metric tag는 `source`, `outcome`, `transport`처럼 유한한 값만 사용한다. raw
lock name, leader ID, endpoint, error message는 metric tag가 되지 않는다.
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
