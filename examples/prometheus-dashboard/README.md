# examples-prometheus-dashboard

English | [한국어](README.ko.md)

Runnable Spring Boot 4 example that exposes bluetape4k leader election metrics
through `/actuator/prometheus` and visualizes them in Prometheus and Grafana.

## Scenario

The Spring Boot app schedules a proxied `@LeaderElection` job named
`dashboard-job`. The job records leader AOP metrics through Micrometer, exposes
them from `/actuator/prometheus`, and the compose stack lets Prometheus scrape
the app while Grafana renders the pre-provisioned dashboard.

## Example Scenario

![Prometheus dashboard scenario diagram](../../docs/images/readme-diagrams/examples-prometheus-dashboard-scenario-01.png)

## Architecture Diagram

![prometheus dashboard Architecture diagram](../../docs/images/readme-diagrams/examples-prometheus-dashboard-architecture-01.png)

## Flow Diagram

![Prometheus dashboard flow diagram](../../docs/images/readme-diagrams/examples-prometheus-dashboard-flow-01.png)

## Sequence Diagram

![prometheus dashboard Sequence Flow diagram](../../docs/images/readme-diagrams/examples-prometheus-dashboard-sequence-01.png)

## Core Features

- `@Scheduled` trigger that calls a proxied `@LeaderElection` job named `dashboard-job`
- Lettuce Redis backend with a local Testcontainers fallback for `bootRun`
- Micrometer leader AOP metrics exposed through Spring Boot Actuator
- Local demo `ObservationHandler` for leader Micrometer Observations
- Prometheus scrape config and a hand-authored Grafana dashboard
- Static lock metric pre-registration so the dashboard shows series immediately
- Spring Boot AOT processing for the application and Spring test context

## Run Locally

```bash
./gradlew :examples:prometheus-dashboard:bootRun
curl http://localhost:8080/actuator/prometheus | grep leader_aop
```

`bootRun` uses Testcontainers Redis unless `DEMO_REDIS_URL` is set.
The demo also logs leader observations from a local `ObservationHandler`; disable it with `DEMO_OBSERVATION_LOGGING_HANDLER_ENABLED=false`.

The example opts into raw metric lock labels because it uses one static job name:

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        tags:
          lock-name:
            mode: RAW
```

Keep the default `REDACT` mode in real services when lock names contain tenant, user, request, or unbounded job identifiers. Use `HASH` when a dashboard needs bounded correlation without raw labels.

## Observation Tracing Demo

`application.yml` enables the leader Observation bridge with safe defaults:

![leader metrics and Observation tracing bridge architecture](../../docs/images/readme-diagrams/leader-micrometer-architecture-01.png)

```yaml
bluetape4k:
  leader:
    observability:
      tracing:
        enabled: true
        include-lock-name: false
        include-leader-id: false
        include-exception-details: false
```

`PrometheusDashboardApp` registers `LeaderObservationLoggingHandler` as a local demo hook. It logs observation names and low-cardinality key values for `leader.aop.acquire`, `leader.aop.execution`, and listener events, but it is not production export configuration.

This example intentionally does not add an OpenTelemetry SDK, Micrometer tracing bridge, exporter, or collector. Add those dependencies in the application when exported traces are required. Observation `lock.name`, `leader.id`, and exception details are disabled because they can contain tenant, user, job, URL, or credential-like values. Current Spring AOP does not synthesize `leader.id`; that value appears only when a direct or future identity-aware path supplies `LeaderAopMetricsContext.Identified`.

Lease-extension observations are also out of scope for this example until `LockExtender` exposes a core observation/event hook; that follow-up is tracked in issue #559.

## Run With Prometheus And Grafana

```bash
cd examples/prometheus-dashboard
cp .env.example .env
docker compose up --build
```

Endpoints:

| Service | URL |
|---|---|
| Spring Boot app | <http://127.0.0.1:8080> |
| Prometheus | <http://127.0.0.1:9090> |
| Grafana | <http://127.0.0.1:3000> |

The compose file binds host ports to `127.0.0.1`, does not publish Redis, and
exposes only `prometheus,health,info` actuator endpoints. Change the Grafana
password in `.env` before sharing the stack outside a local workstation.

## Prometheus Queries

```promql
sum by (lock_name) (rate(leader_aop_attempts_total[1m]))
sum by (lock_name) (rate(leader_aop_acquired_total[1m]))
sum by (lock_name, reason) (rate(leader_aop_lock_not_acquired_total[5m]))
sum by (lock_name) (rate(leader_aop_execution_duration_seconds_sum[1m]))
  / sum by (lock_name) (rate(leader_aop_execution_duration_seconds_count[1m]))
max by (lock_name) (leader_aop_active)
```

Use `max by (lock_name) (leader_aop_active)` for the active gauge in
multi-instance deployments. The gauge is JVM-local, so `sum` can over-count.

## Configuration

| Property / Env | Default | Description |
|---|---:|---|
| `DEMO_REDIS_URL` / `demo.redis.url` | Testcontainers Redis | Redis URI used by Lettuce |
| `DEMO_JOB_FIXED_DELAY_MS` / `demo.job.fixed-delay-ms` | `5000` | Scheduler fixed delay |
| `DEMO_JOB_INITIAL_DELAY_MS` / `demo.job.initial-delay-ms` | `1000` | Initial scheduler delay |
| `DEMO_OBSERVATION_LOGGING_HANDLER_ENABLED` / `demo.observation.logging-handler-enabled` | `true` | Enables the local demo Observation handler |
| `bluetape4k.leader.aop.metrics.tags.lock-name.mode` | `RAW` | Demo-only opt-in so the static `dashboard-job` label is visible in Prometheus |
| `SERVER_PORT` | `8080` | HTTP port |

## Dependencies

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot:${bluetape4kVersion}")
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer:${bluetape4kVersion}")
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce:${bluetape4kVersion}")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Add Micrometer tracing / OpenTelemetry exporter dependencies in the application
    // only when you want to export Observations as traces.
}
```

The example module declares `@EnableAspectJAutoProxy(proxyTargetClass = true)`
so this application module can demonstrate `@LeaderElection` without
compile-time weaving. The scheduled trigger delegates to a separate proxied job
bean; that explicit proxy boundary keeps the example behavior stable under
Spring scheduling.

The module also applies Spring Boot's AOT plugin. The default CI check runs
`processAot` and `processTestAot` before the integration test; native image
generation is intentionally left out of the default path because it requires a
GraalVM/native-image toolchain. The AOT tasks use the same Testcontainers Redis
fallback as `bootRun`, so Docker must be available unless `DEMO_REDIS_URL` is
set.

## Testing

```bash
./gradlew :examples:prometheus-dashboard:processAot \
  :examples:prometheus-dashboard:processTestAot \
  :examples:prometheus-dashboard:test
```

The test starts Spring Boot on a random port, uses the shared
`RedisServer.Launcher.redis` Testcontainers singleton, and verifies the
Prometheus scrape contains `leader_aop_*` metrics for `dashboard-job`.
