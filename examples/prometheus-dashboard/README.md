# examples-prometheus-dashboard

[한국어](./README.ko.md) | English

Runnable Spring Boot 4 example that exposes bluetape4k leader election metrics
through `/actuator/prometheus` and visualizes them in Prometheus and Grafana.

## Architecture

![Architecture diagram](../../docs/images/readme-diagrams/examples-prometheus-dashboard-architecture-01.png)

## Core Features

- `@Scheduled` trigger that calls a proxied `@LeaderElection` job named `dashboard-job`
- Lettuce Redis backend with a local Testcontainers fallback for `bootRun`
- Micrometer leader AOP metrics exposed through Spring Boot Actuator
- Prometheus scrape config and a hand-authored Grafana dashboard
- Static lock metric pre-registration so the dashboard shows series immediately
- Spring Boot AOT processing for the application and Spring test context

## Run Locally

```bash
./gradlew :examples:prometheus-dashboard:bootRun
curl http://localhost:8080/actuator/prometheus | grep leader_aop
```

`bootRun` uses Testcontainers Redis unless `DEMO_REDIS_URL` is set.

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
rate(leader_aop_execution_duration_seconds_sum[1m])
  / rate(leader_aop_execution_duration_seconds_count[1m])
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
| `SERVER_PORT` | `8080` | HTTP port |

## Dependencies

```kotlin
dependencies {
    implementation(project(":leader-spring-boot"))
    implementation(project(":leader-micrometer"))
    implementation(project(":leader-redis-lettuce"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
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
