# Issue #230 Prometheus Dashboard Example Design

## Context

Issue #230 asks for a runnable Spring Boot example that exposes leader election
metrics through Spring Boot Actuator's `/actuator/prometheus` endpoint and ships
Prometheus plus Grafana configuration.

The recently merged leader observability and lifecycle work makes this example
the best adoption path for users who want to see leader election behavior in a
real monitoring stack.

## Goals

- Add `examples/prometheus-dashboard`.
- Demonstrate a `@Scheduled` trigger that calls a proxied `@LeaderElection`
  leader-only job.
- Use `leader-spring-boot`, `leader-micrometer`, and `leader-redis-lettuce`.
- Expose `/actuator/prometheus` with leader metrics.
- Provide Docker Compose with app, Prometheus, and Grafana.
- Add a focused scrape test that verifies leader metrics are present.
- Apply Spring Boot AOT processing to the example app and Spring test context.
- Wire the new example into settings, CI, the Examples workflow, and root
  README links.

## Non-Goals

- No new leader library API.
- No production-grade Grafana dashboard beyond a useful starter board.
- No Kubernetes deployment.
- No native image build in default CI; GraalVM/native-image remains outside this
  example PR's verification budget.

## Design

### Spring Boot App

`PrometheusDashboardApp` is a Spring Boot 4 application with scheduling enabled.
It provides:

- a Testcontainers Redis-backed default connection for local `bootRun`;
- an overrideable `DEMO_REDIS_URL` for Docker Compose Redis;
- a `LeaderScheduledTrigger` with `@Scheduled`;
- a proxied `LeaderScheduledJob` with
  `@LeaderElection(bean = "lettuceLeaderElectionFactory")`;
- startup metric pre-registration for `dashboard-job` through
  `MicrometerLeaderAopMetricsRecorder.registerMetricsFor`.

The example is a user application module, so it uses
`@EnableAspectJAutoProxy(proxyTargetClass = true)` and an explicit scheduling
proxy boundary instead of trying to weave library Spring-managed aspects from an
external aspect path. A runtime scrape assertion must verify that a scheduled
tick increments leader AOP counters, not only that pre-registered zero-value
meters exist.

The scheduled job uses the static literal lock name `dashboard-job` and disables
the default AOP lock-name prefix to avoid dynamic tag cardinality and keep the
dashboard simple. Default timing is `fixedDelay = 5s`, `waitTime = 1s`, and
`leaseTime = 30s`, giving visible samples within one minute while keeping each
tick shorter than the lease. If virtual threads are enabled, the example code
must avoid `synchronized` blocks.

`LeaderMetricsPreRegistrar` is a Spring component that constructor-injects the
required, non-null `MicrometerLeaderAopMetricsRecorder` and registers
`dashboard-job` from an `ApplicationReadyEvent` listener. Missing recorder
auto-configuration should fail startup rather than silently skipping
pre-registration.

### Prometheus/Grafana

Spring Boot Actuator exposes Prometheus metrics at `/actuator/prometheus` when
the Prometheus registry is present and the endpoint is included in
`management.endpoints.web.exposure.include`.

Compose topology:

- `app`: runs the Spring Boot example.
- `redis`: lock backend for app.
- `prometheus`: scrapes `app:8080/actuator/prometheus`.
- `grafana`: provisions a Prometheus datasource and leader dashboard.

Compose hardening:

- Pin image versions; do not use `latest`.
- Do not publish Redis to the host.
- Bind app, Prometheus, and Grafana host ports to `127.0.0.1`.
- Use `.env.example` for Grafana credentials and disable sign-up.
- Add a root `.dockerignore` because the Compose build context is the repository
  root; local `.env`, `.git`, Gradle outputs, worktrees, and agent artifacts
  must not enter image layers.
- Expose only `prometheus,health,info` actuator endpoints.
- Use a checked-in Dockerfile based on a pinned JRE image.
- Add healthchecks for app and Prometheus so service dependencies become ready
  in a predictable order.

Grafana dashboard JSON is hand-authored for this repository, not copied from
grafana.com.

### Test

`PrometheusScrapeTest` starts the application on a random port with
Testcontainers Redis and polls `/actuator/prometheus` until:

- `leader_aop_attempts_total` is present;
- `leader_aop_acquired_total` is present;
- `leader_aop_active` is present;
- `lock_name="dashboard-job"` is present.

The test contract:

- uses `@SpringBootTest(webEnvironment = RANDOM_PORT)`;
- uses `@DynamicPropertySource` to provide `DEMO_REDIS_URL` from
  `RedisServer.Launcher.redis`;
- uses `awaitility-kotlin` with a max wait of 30 seconds;
- includes `src/test/resources/junit-platform.properties` and
  `src/test/resources/logback-test.xml`;
- asserts literal Prometheus strings because `MicrometerNames` is `internal` and
  should not be made public for the example.

### Spring AOT

The example applies Spring Boot's `org.springframework.boot.aot` plugin directly.
According to Spring Boot's Gradle plugin documentation, the direct AOT plugin is
the right path when an application wants to run AOT processing on the JVM rather
than configuring native image generation. CI and the Examples workflow run:

- `:examples:prometheus-dashboard:processAot`;
- `:examples:prometheus-dashboard:processTestAot`;
- `:examples:prometheus-dashboard:test`.

`processTestAot` prepares the Spring TestContext at build time and uses the
test's `DynamicPropertySource`, so it belongs in the Docker-backed example job
where Testcontainers Redis is available.

## Acceptance Criteria

- `./gradlew :examples:prometheus-dashboard:test` passes.
- `./gradlew :examples:prometheus-dashboard:processAot` passes.
- `./gradlew :examples:prometheus-dashboard:processTestAot` passes.
- `./gradlew :examples:prometheus-dashboard:bootRun` starts the app locally.
- `docker compose up` under the example starts Redis, app, Prometheus, Grafana.
- `/actuator/prometheus` includes leader AOP metrics.
- CI and the Examples workflow know the new example module.
- README and README.ko link to the new example.
- Closes #230 and #145.

## Research Notes

- GNO helper was unavailable in this shell, so repo `rg` over docs/lessons and
  README files was used instead.
- Spring Boot official docs confirm `/actuator/prometheus` is the Prometheus
  scrape endpoint and show a `scrape_configs` entry with
  `metrics_path: "/actuator/prometheus"`.
- Spring Boot Gradle plugin docs confirm direct use of
  `org.springframework.boot.aot` for JVM AOT processing and document
  `processAot` / `processTestAot` as the application/test AOT generation tasks.
- Micrometer docs confirm `PrometheusMeterRegistry.scrape()` emits Prometheus
  text format and Prometheus naming convention transforms counters with
  `_total` suffix.

## Risk Notes

- The example uses Testcontainers Redis for local `bootRun`; Docker is required.
- External `aspect(project(":leader-spring-boot"))` weaving was rejected by
  verification: AspectJ initializes `LeaderElectionAspect` as an AspectJ
  singleton and conflicts with its Spring DI constructor. The example therefore
  uses Spring AOP proxying and a separate scheduled trigger bean.
- CI path filters must include the new example plus its dependent modules.
- Kover is applied to examples by the root build, but examples are excluded from
  publishing/NMCP aggregation and no new threshold is introduced here.
- The example module must ship both `README.md` and `README.ko.md`; root README
  and README.ko must link to it.

## Step 2-R/3-R Advisor Notes

Claude advisor artifact:
`.omx/artifacts/claude-issue-230-prometheus-dashboard-20260516081719.md`.

| Priority | Finding | Decision | Follow-up |
|---|---|---|---|
| P0 | Exact Spring Boot plugin and Freefair aspect configuration missing | revised after verification | Spring Boot plugin kept; external `aspect(project(":leader-spring-boot"))` rejected because it breaks the Spring-managed aspect constructor |
| P0 | Compose security defaults unspecified | accepted | Spec/plan require pinned images, loopback binds, no Redis host port, `.env.example`, scoped actuator endpoints, healthchecks |
| P0 | Test could pass only on pre-registered zero meters | accepted | Test must wait for a scheduled tick and assert counter presence/value |
| P0 | CI/Examples insertion points underspecified | accepted | Plan enumerates CI and Examples workflow edits |
| P1 | Test and README contracts underspecified | accepted | Spec/plan include Spring Boot test shape, test resources, README pairs, hand-authored dashboard |

Latest integrated gate result: P0 = 0, P1 = 0.
