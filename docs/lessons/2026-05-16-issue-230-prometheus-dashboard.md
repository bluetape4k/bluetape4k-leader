# Issue #230 Prometheus Dashboard Example

## Context

Issue #230 added a runnable Spring Boot example for leader AOP metrics with
Prometheus and Grafana. The work also closes #145 by providing the dashboard
adoption path.

## Decision

The example uses a Spring Boot 4 application module with `leader-spring-boot`,
`leader-micrometer`, Lettuce Redis, Actuator Prometheus export, Docker Compose,
and a hand-authored Grafana dashboard. It applies Spring Boot AOT directly and
CI runs both `processAot` and `processTestAot` before the scrape test.

External Freefair `aspect(project(":leader-spring-boot"))` weaving was rejected
after verification. AspectJ tried to initialize `LeaderElectionAspect` as an
AspectJ singleton and conflicted with its Spring DI constructor. The example
therefore uses `@EnableAspectJAutoProxy(proxyTargetClass = true)` and separates
the scheduled trigger bean from the proxied `@LeaderElection` job bean.

The example disables the default lock-name prefix so dashboard labels stay at
the stable `dashboard-job` value. The `Thread.sleep(100)` in the job is
intentional example-only blocking to make execution duration visible.

Docker Compose builds from the repository root so the checked-in `.dockerignore`
must stay in sync with local secret and build-artifact patterns. Without it,
`COPY . .` can put `examples/prometheus-dashboard/.env`, Gradle outputs, or
agent artifacts into an image layer.

Because `processAot` and `processTestAot` instantiate the same Redis fallback
path as the app/test, they require Docker unless `DEMO_REDIS_URL` points to an
existing Redis instance.

## Outcome

The new `examples/prometheus-dashboard` module is registered in Gradle, root
README files, CI, Nightly, and AGENTS guidance. Local verification confirmed
Spring AOT generation, Spring test AOT generation, Prometheus scrape metrics,
workflow syntax, and Gradle project registration.

## Verification

- `./gradlew :examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test --no-configuration-cache --console=plain`
- `./gradlew :examples:prometheus-dashboard:build -x test --no-configuration-cache --console=plain`
- `./gradlew projects --no-configuration-cache --console=plain`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml`
- `git diff --check`

## Future Guidance

For user-facing Spring Boot examples in this repo, do not assume external
AspectJ aspect-path weaving works with Spring-managed constructor-injected
aspects. Verify the actual advice path with a metric or behavior assertion, and
keep Spring AOT tasks in the Docker-backed job when `processTestAot` needs
Testcontainers-backed `DynamicPropertySource`.
