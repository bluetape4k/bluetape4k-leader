# leader-micrometer

[English](README.md) | 한국어

bluetape4k leader election을 위한 Micrometer 계측 모듈입니다.

---

## 개요

`leader-micrometer`는 여섯 가지 계측 경로를 제공합니다.

- `leader-spring-boot`의 어노테이션 AOP를 위한 `MicrometerLeaderAopMetricsRecorder`
- Micrometer Observation tracing bridge를 위한 `MicrometerObservationLeaderAopMetricsRecorder`, `MicrometerObservationLeaderElectionListener`
- elector를 직접 호출할 때 사용하는 `InstrumentedLeaderElector`, `InstrumentedLeaderGroupElector`, `InstrumentedSuspendLeaderElector` 데코레이터
- `LeaderElectionListenerRegistry` 생명주기 callback을 counter로 기록하는 `MicrometerLeaderElectionListener`
- history sink 상태 counter를 위한 `MicrometerSafeLeaderHistoryRecorder`, `MicrometerSuspendSafeLeaderHistoryRecorder`
- bounded audit export counter와 gauge를 위한 `MicrometerLeaderAuditExporter`

이 모듈은 `leader-core`, Micrometer core, Micrometer Observation에 의존합니다. Prometheus, Datadog, OTLP 같은 metric export 형식은 애플리케이션이 선택한 Micrometer registry가 결정합니다. Observation export는 애플리케이션이 추가한 Micrometer tracing bridge와 exporter가 결정합니다.

## 아키텍처

![leader-micrometer instrumentation architecture diagram](../docs/images/readme-diagrams/leader-micrometer-architecture-01.png)

## 의존성

```kotlin
implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer:0.4.0")

// 애플리케이션에서 사용할 registry를 선택합니다.
implementation("io.micrometer:micrometer-registry-prometheus")
```

Spring Boot AOP 메트릭을 사용할 때:

```kotlin
implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot:0.4.0")
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

## Spring AOP 메트릭

`leader-spring-boot`, `leader-micrometer`, `MeterRegistry` bean이 함께 있으면 Spring 자동 구성이 `MicrometerLeaderAopMetricsRecorder`를 등록합니다. `ObservationRegistry` bean도 있으면 Observation recorder를 추가로 등록할 수 있습니다. 두 recorder는 서로 대체 관계가 아니라 함께 동작하는 보완 계층입니다.

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        enabled: true
        tags:
          lock-name:
            mode: REDACT
            redacted-value: redacted-lock
          leader-id:
            mode: REDACT
            redacted-value: redacted-leader
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

```kotlin
@Service
class ReportJobs {
    @LeaderElection(name = "daily-report")
    fun generate(): Report? =
        reportService.generate()
}
```

### 태그 Cardinality 제어

메트릭은 tag 값을 export하기 전에 `LeaderMetricTagOptions`를 적용합니다. 운영 기본값은 동적 `lock.name`을 `redacted-lock`으로, opt-in `leader.id` Observation 값을 `redacted-leader`로 redaction합니다. active diagnostics meter는 정제된 제한 범위의 `backend.name`만 emit합니다. 다른 built-in meter path는 `backend.name`을 emit하지 않습니다. 이렇게 하면 Prometheus, Datadog, OTLP backend에 tenant, request, job id마다 새로운 time series가 생기는 일을 막을 수 있습니다.

애플리케이션 정책은 Spring property로 설정합니다.

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        tags:
          lock-name:
            mode: HASH
            hash-length: 12
            allow-list:
              - daily-report
              - nightly-cleanup
            deny-list:
              - tenant-debug-job
```

| Mode | 동작 | 주 사용처 |
|---|---|---|
| `REDACT` | 설정한 sentinel 값으로 export | 동적 이름 기본값 |
| `RAW` | 원본 값을 그대로 export | 작고 정적인 job set |
| `HASH` | 결정적인 SHA-256 hex prefix export | 상관관계 확인용, 익명화 아님 |
| `TRUNCATE` | 제한된 prefix export, `max-length > 0` 필요 | 길이 제한이 있는 기존 dashboard |

Denylist가 항상 우선 redaction됩니다. allowlist가 비어 있지 않으면 정확히 일치하는 값만 raw로 통과하고 나머지는 redaction됩니다. 단 `TRUNCATE`는 allow된 값에도 최대 길이를 적용합니다. 프로세스별 custom rule source가 필요하면 `LeaderMetricTagSanitizer`를 Spring bean이나 생성자 인자로 제공할 수 있습니다.

`HASH`는 결정적이고 salt가 없는 pseudonymization입니다. entropy가 낮은 tenant, user, job 이름은 dictionary attack으로 추정될 수 있고, raw 값마다 time series가 하나씩 생기므로 cardinality도 줄지 않습니다. PII, secret, tenant ID, user ID, 무제한 이름에는 위험 모델을 문서화하지 않는 한 `REDACT`를 사용하세요. allowlist는 민감하지 않고 cardinality가 제한된 정적 이름에만 사용합니다.

## Observation Tracing

leader 실행을 Micrometer Observation으로 남기고, 애플리케이션이 가진 tracing bridge가 span으로 변환하게 하려면 `MicrometerObservationLeaderAopMetricsRecorder`를 사용합니다.

```kotlin
val recorder = MicrometerObservationLeaderAopMetricsRecorder(
    registry = observationRegistry,
    options = LeaderObservationOptions(
        includeLockName = false,
        includeLeaderId = false,
        includeExceptionDetails = false,
        tagOptions = LeaderMetricTagOptions.Default,
    ),
)

recorder.onLockAcquired("daily-report", LeaderElectionOptions.Default, 12.milliseconds)
```

이 recorder는 terminal callback마다 짧은 standalone observation을 기록합니다. 보호된 메서드 본문 전체를 새 `Observation.Scope`으로 감싸지 않습니다. 현재 AOP SPI에는 동일 lock의 동시 실행에서 start/stop을 안전하게 짝지을 per-invocation id가 없기 때문입니다.

listener-aware elector의 생명주기 event는 `MicrometerObservationLeaderElectionListener`로 관찰할 수 있습니다.

```kotlin
val listener = MicrometerObservationLeaderElectionListener(observationRegistry)
val election = LocalLeaderElector().apply {
    addListener(listener)
}
```

이 모듈은 Micrometer Observation만 발생시킵니다. OpenTelemetry SDK, tracing bridge, exporter, collector는 추가하지 않습니다. trace export가 필요하다면 애플리케이션이 해당 의존성과 설정을 직접 추가해야 합니다.

### Lease-extension 관찰

> **미배포 API:** 이 절은 현재 `develop` 구현을 설명합니다. 위 의존성 예제는 배포된 `0.4.0`을 대상으로 하며,
> 고정한 `0.5.0` 매뉴얼에는 이 hook이 없습니다. 초안의 promotion gate가 끝날 때까지는 일치하는
> `develop` 브랜치 또는 일치하는 미배포 빌드에서만 이 연동을 사용하세요.

`MicrometerObservationLeaderLeaseExtensionObserver`는 core의
`LeaderLeaseExtensionEvent`를 짧은 terminal Observation으로 변환합니다.
`LockExtender`와 `LeaderLeaseAutoExtender`가 사용하는 process-local core registry에
등록하세요.

```kotlin
val observer = MicrometerObservationLeaderLeaseExtensionObserver(
    registry = observationRegistry,
    options = LeaderObservationOptions(),
)
val registration = LeaderLeaseExtensionObservers.addObserver(observer)

// 이 blocking 예제는 일치하는 활성 @LeaderElection 또는 @LeaderGroupElection scope 안에서 호출하세요. 그렇지 않으면
// NotHeld를 반환합니다. 직접 elector의 active lease body 안에서도 같은 규칙이 적용됩니다. Suspend scope에서는
// suspend 함수 안에서 extendActiveLockDetailedSuspend(60.seconds)를 사용하세요.
try {
    LockExtender.extendActiveLockDetailed(60.seconds)
} finally {
    registration.close()
}
```

Observation 이름은 `bluetape4k.leader.lease.extension`입니다. bounded
low-cardinality tag는 `source`, `execution`, `outcome`, `result`입니다.

| `ExtendOutcome` | `outcome` | `result` |
|---|---|---|
| `Extended` | `extended` | `success` |
| `Rejected` | `rejected` | `skipped` |
| `NotHeld` | `not_held` | `skipped` |
| `WrongThread` | `wrong_thread` | `error` |
| `BackendError` | `backend_error` | `error` |

`elapsedNanos`는 tag로 기록하지 않습니다. `includeLockName`과 `includeLeaderId`는
명시적으로 켰을 때만 sanitised high-cardinality 값을 추가하고,
`includeExceptionDetails`는 tag sanitiser를 거치지 않은 원본 backend 예외를
`Observation.error(...)`로 연결합니다. 기본 옵션에서는 세 값이 모두 꺼져 있습니다.
Downstream observation 또는 tracing 시스템이 raw exception message와 stack trace를
받아도 되는 경우에만 exception detail을 켜세요. `NOOP ObservationRegistry`에서는
Observation을 만들지 않습니다. 이 모듈은 Micrometer Observation만 발생시키며 tracing
bridge, exporter, collector, OpenTelemetry SDK는 애플리케이션이 추가해야 합니다.
Issue #529는 acquire/execution observation을 담당하고, 이 Issue #559 adapter는
terminal lease-extension 시도를 담당합니다. Spring Boot auto-configuration을
사용하는 경우 `addObserver`를 수동으로 다시 호출하지 마세요. Spring manager가
registry identity마다 registration 하나를 공유합니다.
위 snippet은 하나의 명시적 `USER` 시도 뒤에 registration을 닫습니다. `WATCHDOG` tick이 필요하면
`autoExtend = true`인 단일 리더 action 또는 component 전체 수명 동안 registration을 유지하고 종료 시 닫으세요.
Group election slot은 active body 안의 명시적 `LockExtender` 호출은 지원하지만 group auto-extension이 꺼져
있으므로 `WATCHDOG` event를 만들지 않습니다.
전체 core 계약과 Spring lifecycle은
[미배포 lease-extension 관찰 초안](../docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.ko.md)에서
확인할 수 있습니다.

## 직접 Elector 메트릭

elector를 직접 호출한다면 데코레이터를 사용합니다.

```kotlin
val delegate = RedissonLeaderElector(redisson)
val election = InstrumentedLeaderElector(delegate, registry)

val result = election.runIfLeader("daily-report") {
    reportService.generate()
}
```

```kotlin
val group = InstrumentedLeaderGroupElector(groupDelegate, registry)
group.runIfLeader("batch-shard") {
    processShard()
}
```

```kotlin
val suspendElection = InstrumentedSuspendLeaderElector(suspendDelegate, registry)
suspendElection.runIfLeader("sync-job") {
    syncService.sync()
}
```

데코레이터 생성자에 `lockName = "static-job"`을 넘기면 실제 호출 lock 이름과 무관하게 고정 `lock.name` 태그를 사용합니다.

Decorator와 listener 메트릭도 AOP 메트릭과 같은 sanitizer 기본값을 사용합니다. lock name 집합이 작고 고정되어 있다는 확신이 있을 때만 `LeaderMetricTagOptions.Raw`를 넘기세요.

```kotlin
val election = InstrumentedLeaderElector(
    delegate = delegate,
    registry = registry,
    tagOptions = LeaderMetricTagOptions.Raw,
)
```

세 instrumented elector decorator는 delegate의
`LeaderBackendDiagnosticsProvider`도 노출합니다. Active
`checkConnectivity`와 `diagnostics(probe = true)` 호출은
`backend.name`, `status`, `reason` tag와 함께 `leader.backend.connectivity`를
호출마다 한 번 증가시킵니다. Passive `diagnostics()`는 meter를 만들지
않습니다. Decorator는 제한된 enum 값만 기록하고 provider의 원래 예외를
재전파하며, 예외 원문·endpoint·credential·lock name을 export하지 않습니다.

## Listener 이벤트 메트릭

elector를 instrumented decorator로 감싸지 않고 생명주기 counter만 기록하려면 `MicrometerLeaderElectionListener`를 사용합니다.

```kotlin
val listener = MicrometerLeaderElectionListener(registry)
val election = LocalLeaderElector().apply {
    addListener(listener)
}

election.runIfLeader("daily-report") {
    reportService.generate()
}
```

## Meter Catalog

### AOP Meter

| Meter | 타입 | 태그 | 설명 |
|-------|------|------|------|
| `leader.aop.attempts` | Counter | `lock.name` | 락 획득 시도 |
| `leader.aop.acquired` | Counter | `lock.name` | 리더 실행 성공 |
| `leader.aop.lock.not.acquired` | Counter | `lock.name`, `reason` | 경쟁, backend 오류, fail-open 경로에 의한 skip |
| `leader.aop.execution.duration` | Timer | `lock.name` | 성공한 본문 실행 시간 |
| `leader.aop.task.failed` | Counter | `lock.name`, `exception` | 사용자 본문 예외 |
| `leader.aop.active` | Gauge | `lock.name` | 현재 JVM에서 실행 중인 리더 본문 수 |

### 직접 Elector Meter

| Meter | 타입 | 태그 | 설명 |
|-------|------|------|------|
| `shedlock.leader.acquired` | Counter | `lock.name` | 데코레이터 실행 성공 |
| `shedlock.leader.not_acquired` | Counter | `lock.name` | 데코레이터 skip |
| `shedlock.leader.duration` | Timer | `lock.name` | 데코레이터 본문 실행 시간 |
| `shedlock.leader.active` | Gauge | `lock.name` | 현재 JVM에서 실행 중인 데코레이터 본문 수 |
| `leader.backend.connectivity` | Counter | `backend.name`, `status`, `reason` | active backend connectivity probe마다 한 번 기록 |

### Listener 이벤트 Meter

| Meter | 타입 | 태그 | 설명 |
|-------|------|------|------|
| `leader.election.events` | Counter | `lock.name`, `event` | 생명주기 callback: `elected`, `revoked`, `skipped` |

### Observation 이름

| Observation | Low-cardinality key | High-cardinality key |
|---|---|---|
| `leader.aop.acquire` | `leader.operation`, `outcome`, `reason` | `acquire.elapsed.ms`, 옵션을 켠 경우에만 `lock.name`, `leader.id` |
| `leader.aop.execution` | `leader.operation`, `outcome`, `exception` | `execution.elapsed.ms`, 옵션을 켠 경우에만 `lock.name`, `leader.id` |
| `leader.election.event` | `event` | 옵션을 켠 경우에만 `lock.name` |

`CancellationException`은 `outcome=cancelled`로 기록하고 `Observation.error(...)`로 넘기지 않습니다. cancellation이 아닌 실패는 기본적으로 예외 simple class name만 남깁니다. raw throwable은 `LeaderObservationOptions(includeExceptionDetails = true)`를 명시한 경우에만 첨부합니다.

### History Sink Meter

| Meter | 타입 | 태그 | 설명 |
|-------|------|------|------|
| `leader.history.sink.failures` | Counter | `sink` | cancellation/interruption 경로를 제외한 history sink 호출 실패 |
| `leader.history.acquire.missing` | Counter | `sink` | 사용할 수 없거나 중복된 acquire record 때문에 `recordAcquired`가 `null`을 반환 |

## Audit Export 메트릭

bounded audit delivery 결과를 Micrometer로 export하려면 core
`LeaderAuditExporter`를 감쌉니다.

```kotlin
val exporter = MicrometerLeaderAuditExporter(delegate, registry)
exporter.submit(event)
// ACCEPTED는 admission만 의미하며 delivery 성공을 의미하지 않습니다.
exporter.close() // delegate를 정확히 한 번 소유하고 닫습니다.
```

core 모듈의 JDK HTTP transport와 Micrometer decorator를 조합하면 webhook 결과와
queue gauge를 함께 export할 수 있습니다.

```kotlin
val exporter = MicrometerLeaderAuditExporter(
    delegate = HttpLeaderAuditExporter(
        client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(
            URI("https://audit.example.test/v1/leader-events"),
        ),
        headers = mapOf("Authorization" to "Bearer ${System.getenv("AUDIT_WEBHOOK_TOKEN")}"),
        encoder = LeaderAuditPayloadEncoder { event ->
            LeaderAuditHttpPayload.of("text/plain; charset=utf-8", event.toString().toByteArray())
        },
        exportOptions = exportOptions,
        httpOptions = LeaderAuditHttpOptions.defaults(),
    ),
    registry = registry,
)
```

`HttpLeaderAuditExporter`는 신뢰한 HTTPS endpoint만 받고 redirect를 끄며 응답 body를
폐기합니다. 직렬화, endpoint allow-list, idempotency는 애플리케이션의 책임입니다.
이 모듈은 메트릭만 추가하고 JSON 또는 OpenTelemetry transport는 추가하지 않습니다.

decorator는 고정 aggregate metric catalog 하나만 제공합니다. lock name,
leader ID, endpoint, error message, `source`, `transport`를 tag로 복사하지
않습니다. 유일한 tag는 아래의 제한된 `outcome` 값입니다. registry는 close 후
replacement generation에서도 meter identity를 유지하므로 `MeterRegistry.remove`
를 호출하거나 다른 컴포넌트에서 고정 ID를 등록하지 마세요. 동일 registry에서
active wrapper를 중복 생성하거나 foreign fixed-ID가 발견되면 즉시 실패합니다.
non-owning observation이 필요하면 같은 delegate를 두 번 wrapping하지 말고
`delegate.observe(...)`로 observer를 등록하세요.

| Meter | 타입 | Tag / outcome | Snapshot source |
|---|---|---|---|
| `leader.audit.export.accepted` | FunctionCounter | `outcome=accepted` | `accepted` |
| `leader.audit.export.dropped` | FunctionCounter | `outcome=queue_full` 또는 `closed` | `droppedQueueFull`, `droppedClosed` |
| `leader.audit.export.retries` | FunctionCounter | `outcome=retry` | `retries` |
| `leader.audit.export.failures` | FunctionCounter | `outcome=failure` | `terminalFailures` |
| `leader.audit.export.queue.depth` | Gauge | 없음 | `queued` |
| `leader.audit.export.in.flight` | Gauge | 없음 | `inFlight` |
| `leader.audit.export.cancelled` | FunctionCounter | `outcome=cancelled` | `cancellations` |
| `leader.audit.export.rejections` | FunctionCounter | `outcome=rejected` | executor + scheduler rejection 합계 |
| `leader.audit.export.observer.dropped` | FunctionCounter | 없음 | `observerDrops` |
| `leader.audit.export.observer.registration.dropped` | FunctionCounter | 없음 | `observerRegistrationDrops` |
| `leader.audit.export.diagnostics.failures` | FunctionCounter | 없음 | `diagnosticsFatalErrors` |
| `leader.audit.export.diagnostics.closed` | Gauge | 없음 | `diagnosticsClosed` |

dropped meter는 두 개의 outcome-tagged ID를 가지므로 고정 catalog는 총 13개
meter ID입니다. Counter 값은 detached generation offset과 active delegate
기준 데이터 값을 합산해 replacement 후에도 감소하지 않습니다. close 중 delegate
기준 데이터를 읽다가 예외가 나면 마지막으로 신뢰한 offset을 유지하고 source를
degraded로 표시한 뒤 delegate reference를 분리하고 원래 예외를 전달합니다. 기준 데이터가
더 낮은 cumulative 값을 반환하는 경우에는 trusted baseline을 유지하고 degraded warning만
기록한 뒤 정상 반환하며, 예외를 새로 만들지 않습니다.
degraded 경로는 `leader.audit.export.meter-source-degraded` fixed warning을 사용하며
응답 payload나 exception message를 로그에 남기지 않습니다.
고정 catalog를 일부만 등록한 뒤 registration이 실패하면 manager는 이미 등록한 소유
meter와 identity를 보존합니다. 다음 acquire에서는 누락된 ID만 등록하고 foreign meter는
제거하지 않습니다. 각 scrape의 cumulative 비교는 scalar 연산으로 수행하므로 hot path에서
임시 Boolean collection을 만들지 않습니다.
open generation에서 metric polling 중 `delegate.snapshot()`을 읽지 못하면
decorator는 마지막으로 신뢰한 cumulative·gauge 값을 유지하고
`diagnosticsClosed=0`을 보존하며 해당 generation에서 fixed warning을 최대 한 번만
기록합니다.
각 metric read는 decorator가 소유한 13개 meter의 identity도 다시 확인합니다. 다른
컴포넌트가 고정 ID를 제거하거나 교체하면 manager는 마지막으로 신뢰한 detached 값을
고정하고 `leader.audit.export.meter-ownership-conflict` warning을 한 번만 기록하며
foreign meter를 읽거나 제거하지 않습니다. compromised manager는 재사용하지 않으므로
충돌 등록을 제거한 뒤 새 `MeterRegistry`를 사용해야 복구할 수 있습니다. registry가
달라도 동일 delegate를 두 decorator가 감쌀 수 없으며, 실패한 wrapper는 active owner를
닫지 않습니다.

이번 slice는 Micrometer 메트릭만 제공합니다. JSONL 출력과 OpenTelemetry
SDK/bridge/exporter는 별도 후속 범위이며 애플리케이션이 해당 의존성과 transport를
명시적으로 추가해야 합니다.

Micrometer naming convention이 export backend에 맞춰 이름을 바꿉니다. Prometheus에서는 `leader_aop_attempts_total`, `leader_aop_execution_duration_seconds`, `shedlock_leader_acquired_total` 같은 이름으로 노출됩니다.

## Prometheus Export

Spring Boot에서는 Prometheus registry와 Actuator endpoint를 추가합니다.

```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  endpoint:
    prometheus:
      access: unrestricted
```

Scrape:

```text
GET /actuator/prometheus
```

유용한 PromQL:

```promql
sum by (lock_name) (rate(leader_aop_acquired_total[5m]))
sum by (lock_name, reason) (rate(leader_aop_lock_not_acquired_total[5m]))
histogram_quantile(0.95, sum by (lock_name, le) (rate(leader_aop_execution_duration_seconds_bucket[5m])))
max by (lock_name) (leader_aop_active)
```

`leader.aop.active`, `shedlock.leader.active`는 JVM 로컬 gauge입니다. 여러 인스턴스를 볼 때는 의도적으로 합산해야 하는 경우가 아니라면 `max by (lock_name)`을 우선 사용하세요.

`PrometheusExportTest`는 Micrometer text exposition과 `bluetape4k-testcontainers`의 `PrometheusServer`를 사용한 실제 Prometheus scrape를 함께 검증합니다.
검증 대상은 `leader_aop_acquired_total`, `shedlock_leader_acquired_total` 같은 Prometheus 이름과 변환된 `lock_name` label을 포함합니다.

## 사전 등록

첫 실행 전에도 dashboard에 0 값 series를 보이게 하려면 정적 lock 이름을 사전 등록합니다.

```kotlin
@Component
class MetricsPreRegistrar(
    private val recorder: MicrometerLeaderAopMetricsRecorder,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        recorder.registerMetricsFor("daily-report", "nightly-cleanup")
    }
}
```

## Cardinality 가이드

`lock.name` cardinality는 제한해야 합니다. 요청 ID, 사용자 ID, 무제한 tenant ID를 export되는 metric tag에 그대로 넣지 마세요. 기본 sanitizer는 동적 이름을 하나의 sentinel로 접습니다. dashboard가 raw label이 꼭 필요하다면 정적 job 이름에만 `RAW`를 사용하세요. `HASH`는 raw label 없이 상관관계를 볼 수 있지만, cardinality reduction이나 익명화는 아닙니다.

Observation의 high-cardinality 필드는 기본 비활성입니다. `includeLockName=true` 또는 `includeLeaderId=true`를 켜더라도 값은 export 전에 `LeaderObservationOptions.tagOptions`를 거칩니다. 현재 Spring AOP는 `leader.id`를 합성하지 않습니다. `includeLeaderId=true`는 direct 호출 또는 future identity-aware 경로에서 `LeaderAopMetricsContext.Identified`가 전달될 때만 값을 내보냅니다.

tracing backend가 예외 message와 stack trace를 받아도 되는 환경이 아니라면 `includeExceptionDetails=false`를 유지하세요.

## 정리

더 이상 사용하지 않는 정적 lock 이름은 `deregisterMetricsFor(lockName)`으로 registry에서 제거할 수 있습니다.

```kotlin
recorder.deregisterMetricsFor("old-nightly-job")
```
