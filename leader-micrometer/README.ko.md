# leader-micrometer

[English](README.md)

bluetape4k leader election을 위한 Micrometer 계측 모듈입니다.

---

## 개요

`leader-micrometer`는 두 가지 계측 경로를 제공합니다.

- `leader-spring-boot`의 어노테이션 AOP를 위한 `MicrometerLeaderAopMetricsRecorder`
- elector를 직접 호출할 때 사용하는 `InstrumentedLeaderElector`, `InstrumentedLeaderGroupElector`, `InstrumentedSuspendLeaderElector` 데코레이터
- `LeaderElectionListenerRegistry` 생명주기 callback을 counter로 기록하는 `MicrometerLeaderElectionListener`

이 모듈은 `leader-core`와 Micrometer core에만 의존합니다. Prometheus, Datadog, OTLP 같은 export 형식은 애플리케이션이 선택한 Micrometer registry가 결정합니다.

## 아키텍처

![leader micrometer Architecture diagram](../docs/images/readme-diagrams/leader-micrometer-architecture-01.png)

## 의존성

```kotlin
implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer:0.2.2")

// 애플리케이션에서 사용할 registry를 선택합니다.
implementation("io.micrometer:micrometer-registry-prometheus")
```

Spring Boot AOP 메트릭을 사용할 때:

```kotlin
implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot:0.2.2")
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

## Spring AOP 메트릭

`leader-spring-boot`, `leader-micrometer`, `MeterRegistry` bean이 함께 있으면 Spring 자동 구성이 `MicrometerLeaderAopMetricsRecorder`를 등록합니다.

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        enabled: true
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

### Listener 이벤트 Meter

| Meter | 타입 | 태그 | 설명 |
|-------|------|------|------|
| `leader.election.events` | Counter | `lock.name`, `event` | 생명주기 callback: `elected`, `revoked`, `skipped` |

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

`lock.name` cardinality는 제한해야 합니다. 요청 ID, 사용자 ID, 무제한 tenant ID를 그대로 lock 이름에 넣지 마세요. 동적 이름이 필요하다면 애플리케이션 레벨에서 집계하거나 안정적인 job family만 등록하세요.

## 정리

더 이상 사용하지 않는 정적 lock 이름은 `removeMetricsFor(lockName)`으로 registry에서 제거할 수 있습니다.

```kotlin
recorder.removeMetricsFor("old-nightly-job")
```
