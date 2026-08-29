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

## Alert And Runbook Diagram

![Prometheus alert and runbook diagram](../../docs/images/readme-diagrams/examples-prometheus-dashboard-alert-runbook-01.png)

## Core Features

- `@Scheduled` trigger that calls a proxied `@LeaderElection` job named `dashboard-job`
- Lettuce Redis backend with a local Testcontainers fallback for `bootRun`
- Micrometer leader AOP metrics exposed through Spring Boot Actuator
- Scheduled `PrometheusBackendConnectivityProbe` that records the existing Redis diagnostics provider
  as `leader_backend_connectivity_total` with a bounded timeout
- Local demo `ObservationHandler` for leader Micrometer Observations
- Prometheus scrape config and a hand-authored Grafana dashboard
- Example-scoped Prometheus alert rules and runbook guidance
- No-op history recorder registration so history health meters are visible at zero
- Static lock metric pre-registration so the dashboard shows series immediately
- Spring Boot AOT processing for the application and Spring test context

## Run Locally

```bash
./gradlew :examples:prometheus-dashboard:bootRun
curl http://localhost:8080/actuator/prometheus | grep leader_aop
```

`bootRun` uses Testcontainers Redis unless `DEMO_REDIS_URL` is set.
The demo also logs leader observations from a local `ObservationHandler`; disable it with `DEMO_OBSERVATION_LOGGING_HANDLER_ENABLED=false`.

The example keeps metric lock labels redacted by default. Use `HASH` when a dashboard needs bounded
correlation without raw labels, and reserve `RAW` for profile-gated local demos with static job names.

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        tags:
          lock-name:
            mode: REDACT
            redacted-value: redacted-lock
```

Keep `REDACT` in real services when lock names contain tenant, user, request, or unbounded job identifiers.

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

Lease-extension observations are not logged by this example's default handler. The handler accepts observation names
starting with `leader.`, while the core lease-extension observation is named
`bluetape4k.leader.lease.extension`. Applications that own the handler can opt in to that exact name after reviewing
the bounded tags and privacy options in the [unreleased lease-extension observation draft](../../docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.en.md).

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
password in `.env`, remove Prometheus `--web.enable-lifecycle` if it is not
needed, and put the app, Prometheus, and Grafana behind explicit auth, TLS, and
reverse-proxy controls before exposing the stack outside a local workstation.

Prometheus loads `provisioning/prometheus/rules/leader-alerts.yml` through the
compose-mounted `/etc/prometheus/rules` directory. The rules are example-scoped
starting points; copy and tune windows, severities, and notification routing in
your production monitoring stack.

## Alert Runbooks

| Alert | What it means | Safe first actions |
|---|---|---|
| `LeaderElectionNoAcquisitions` | The job keeps attempting but no instance reports `leader_aop_acquired_total` growth. | Check Redis reachability, lock key contention, scheduler cadence, and whether all instances share the same backend. |
| `LeaderElectionBackendErrors` | The AOP path reports `reason="BACKEND_ERROR"` for lock acquisition. | Inspect app logs around `leader backend error`, Redis health, network errors, and failure-mode settings before restarting workers. |
| `LeaderBackendConnectivityDown` | An active backend probe has reported `status="DOWN",reason="DISCONNECTED"` for 5 minutes. The example rule is warning-only and `notification: no-page`. | Check the existing client, backend reachability, and provider timeout. Do not treat this as proof that a lock is orphaned or force-release a lease. |
| `LeaderBackendConnectivityUnknown` | An active probe has remained `UNKNOWN` with `CLIENT_STATE_UNCONFIRMED` or `PROVIDER_UNSUPPORTED` for 10 minutes. This is an info, no-page signal, not `DOWN`. | Confirm provider capability and native timeout settings. Keep passive `NOT_CHECKED` out of this alert because passive diagnostics emit no counter. |
| `LeaderBackendConnectivityProbeExceptions` | Ordinary provider exceptions were normalized to `UNKNOWN` with `PROVIDER_EXCEPTION` for 10 minutes. The rule is warning-only and `notification: no-page`. | Inspect protected application logs and provider-native diagnostics without copying exception text into labels. Bypass active probes when their latency exceeds the request budget. |
| `LeaderElectionTaskFailures` | An elected task body throws after the lock is acquired. | Use the `exception` label to find the failing code path, inspect application logs, and keep the lock backend running while fixing the task. |
| `LeaderHistorySinkFailures` | A real history/audit sink throws while recording leader history. The demo excludes `NoopLeaderHistorySink`. | Verify sink credentials, schema/index state, write capacity, and retention jobs. Leader execution may still proceed while audit durability is degraded. |
| `LeaderHistoryAcquireMissing` | A real history sink returned no acquire key for elected work. The demo excludes `NoopLeaderHistorySink`, which intentionally returns no key. | Look for duplicate records, storage unavailability, or sink-specific conditional write conflicts. |
| `LeaderActiveGaugeAnomaly` | One JVM reports `leader_aop_active > 1` for the single-leader `dashboard-job` lock. | Inspect the `instance` label, thread dumps, long-running executions, and release/finish logging for that JVM. Scope or exclude group-election locks before copying this rule. |
| `LeaderLeaseRiskHighExecutionTime` | Completed executions average above 24s, 80% of this demo's 30s lease. | Treat it as a delayed symptom: shorten work, increase lease time, or add direct lease-extension instrumentation before using it as a hard production page. |
| `LeaderPrometheusScrapeMissing` | Prometheus has no `up` series for the app or the scrape target is down. | Check app health, compose networking, `/actuator/prometheus`, and the Prometheus target page before debugging leader logic. |

The active gauge is JVM-local. Use `max by (lock_name) (leader_aop_active)` for
cluster dashboards and avoid `sum`, which can over-count across instances.
The anomaly alert intentionally uses the raw `leader_aop_active > 1` series so
the firing alert keeps the offending `instance` label.

`exception` is the exception class name tag. Keep exception-grouped alert and
dashboard views internal, or collapse them to `sum by (lock_name)` when
cardinality or implementation-detail exposure matters.

The backend connectivity counter is `leader_backend_connectivity_total` after
Prometheus naming conversion. Its only labels are the sanitized `backend_name`,
the four status values, and the six bounded reason values. The rules above use
`for` windows and `notification: no-page`; `UNKNOWN` is never rewritten as
`DOWN`, and passive `NOT_CHECKED` diagnostics do not produce a series.

`PrometheusBackendConnectivityProbe` is the producer for this counter. It reuses
the existing Lettuce connection through its diagnostics provider, runs on the
configured fixed delay, and passes `DEMO_BACKEND_PROBE_TIMEOUT_MS` as a bounded
probe budget. The current Lettuce provider reports an open client state as
`UNKNOWN` with `CLIENT_STATE_UNCONFIRMED`; it does not claim `UP` without a
backend round trip. Closed client state reports `DOWN` with `DISCONNECTED`.

This demo registers `MicrometerSafeLeaderHistoryRecorder` with
`NoopLeaderHistorySink` so `leader_history_*` meters are visible. The alert
rules exclude that no-op sink because it intentionally returns no acquire key.
In a real service, wire the recorder around the actual JDBC, R2DBC, MongoDB, or
custom history sink before relying on these alerts.

Lease-extension failure is not exposed by this example's Prometheus meters. The
core publishes a `LeaderLeaseExtensionEvent`; the Micrometer adapter creates an
Observation named `bluetape4k.leader.lease.extension`. This demo's local handler
accepts only names starting with `leader.` and the application does not register
a lease-extension meter. The lease-risk rule therefore uses completed
execution duration as a conservative symptom only. Add an explicitly configured
Micrometer observer or update the app-owned handler after reviewing the
[unreleased lease-extension observation draft](../../docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.en.md)
if the demo should surface that signal.

## Prometheus Queries

```promql
sum by (lock_name) (rate(leader_aop_attempts_total[1m]))
sum by (lock_name) (rate(leader_aop_acquired_total[1m]))
sum by (lock_name, reason) (rate(leader_aop_lock_not_acquired_total[5m]))
sum by (lock_name) (rate(leader_aop_lock_not_acquired_total{reason="BACKEND_ERROR"}[5m]))
sum by (lock_name, exception) (rate(leader_aop_task_failed_total[5m]))
sum by (sink) (rate(leader_history_sink_failures_total[5m]))
sum by (sink) (rate(leader_history_acquire_missing_total[5m]))
sum by (lock_name) (rate(leader_aop_execution_duration_seconds_sum[1m]))
  / sum by (lock_name) (rate(leader_aop_execution_duration_seconds_count[1m]))
max by (lock_name) (leader_aop_active)
sum by (backend_name, status, reason) (rate(leader_backend_connectivity_total[5m]))
sum by (backend_name) (rate(leader_backend_connectivity_total{status="DOWN",reason="DISCONNECTED"}[5m]))
sum by (backend_name, reason) (rate(leader_backend_connectivity_total{status="UNKNOWN"}[5m]))
```

Use `max by (lock_name) (leader_aop_active)` for the active gauge in
multi-instance deployments. The gauge is JVM-local, so `sum` can over-count.

## Configuration

| Property / Env | Default | Description |
|---|---:|---|
| `DEMO_REDIS_URL` / `demo.redis.url` | Testcontainers Redis | Redis URI used by Lettuce |
| `DEMO_JOB_FIXED_DELAY_MS` / `demo.job.fixed-delay-ms` | `5000` | Scheduler fixed delay |
| `DEMO_JOB_INITIAL_DELAY_MS` / `demo.job.initial-delay-ms` | `1000` | Initial scheduler delay |
| `DEMO_BACKEND_PROBE_FIXED_DELAY_MS` / `demo.backend-probe.fixed-delay-ms` | `5000` | Connectivity probe fixed delay |
| `DEMO_BACKEND_PROBE_INITIAL_DELAY_MS` / `demo.backend-probe.initial-delay-ms` | `1000` | Initial connectivity probe delay |
| `DEMO_BACKEND_PROBE_TIMEOUT_MS` / `demo.backend-probe.timeout-ms` | `500` | Positive timeout passed to the existing diagnostics provider |
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

The tests start Spring Boot on a random port, use the shared
`RedisServer.Launcher.redis` Testcontainers singleton, and verify the
Prometheus scrape contains `leader_aop_*` metrics for `dashboard-job` plus the
`UNKNOWN`/`CLIENT_STATE_UNCONFIRMED` connectivity series emitted by
`PrometheusBackendConnectivityProbe`. Unit coverage also exercises `UP`,
`DOWN`, and `PROVIDER_EXCEPTION` labels without creating another Redis client.
