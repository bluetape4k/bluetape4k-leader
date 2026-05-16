# Issue #230 Prometheus Dashboard Example Plan

## Tasks

1. Register `examples:prometheus-dashboard` in `settings.gradle.kts`.
2. Add the example Gradle build:
   - plugins: `application`, `org.jetbrains.kotlin.plugin.spring`,
     `org.springframework.boot`, `io.spring.dependency-management`;
   - direct `org.springframework.boot.aot` plugin application;
   - Spring Boot web/actuator/scheduling runtime;
   - `leader-spring-boot`, `leader-micrometer`, `leader-redis-lettuce`;
   - Micrometer Prometheus registry and Lettuce/Testcontainers dependencies.
   - root `.dockerignore` for the repo-root Docker build context.
3. Implement the Spring Boot example:
   - `PrometheusDashboardApp`;
   - Redis connection configuration with `DEMO_REDIS_URL` override;
   - `LeaderScheduledTrigger` plus proxied `LeaderScheduledJob`;
   - `LeaderMetricsPreRegistrar`;
   - `application.yml` and logging config.
4. Add Prometheus/Grafana assets:
   - 4a. `docker-compose.yml`, Dockerfile, `.env.example`, pinned images,
     loopback port bindings, no Redis host port, app/Prometheus healthchecks.
   - 4b. Prometheus scrape config for `/actuator/prometheus`.
   - 4c. Grafana datasource provisioning.
   - 4d. Hand-authored Grafana dashboard JSON.
5. Add README.md and README.ko.md.
6. Add `PrometheusScrapeTest` and test resources:
   - `@SpringBootTest(webEnvironment = RANDOM_PORT)`;
   - `@DynamicPropertySource` using `RedisServer.Launcher.redis`;
   - `awaitility-kotlin` polling up to 30 seconds;
   - assert a scheduled tick increments leader AOP counters;
   - `src/test/resources/junit-platform.properties`;
   - `src/test/resources/logback-test.xml`.
7. Wire CI/Examples workflows:
   - 7a. `ci.yml` changes outputs.
   - 7b. `ci.yml` paths-filter block, including `examples/prometheus-dashboard/**`,
     `leader-spring-boot/**`, `leader-micrometer/**`, `leader-redis-lettuce/**`,
     and `leader-core/**`.
   - 7c. `ci.yml` `test-examples-prometheus-dashboard` job.
   - 7d. `ci.yml` build/test status aggregator `needs` entries.
   - 7e. `examples.yml` `examples-prometheus-dashboard` matrix entry.
   - 7f. keep examples out of `nightly.yml`.
8. Update root README links.
9. Verify:
   - application AOT processing;
   - Spring test AOT processing;
   - targeted test;
   - compile-only build for the new example;
   - `./gradlew projects`;
   - `actionlint` for workflow edits when available.
10. Run final code review on the diff, fix P0/P1 findings.
11. Add lesson, commit, push, create PR.

## Verification Commands

```bash
./gradlew projects
./gradlew :examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test --no-configuration-cache --console=plain
./gradlew :examples:prometheus-dashboard:build -x test --no-configuration-cache --console=plain
actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/examples.yml
```

## Step 2-R/3-R Advisor Notes

Claude advisor artifact:
`.omx/artifacts/claude-issue-230-prometheus-dashboard-20260516081719.md`.

Accepted P0/P1 edits:

- explicit Spring Boot plugin;
- rejected external Freefair `aspect(project(":leader-spring-boot"))` after
  runtime verification showed it conflicts with the Spring-managed aspect
  constructor;
- Compose hardening and image/build choice;
- stronger scrape test contract;
- explicit CI/Examples workflow edit locations;
- README pair and test resource requirements.

Latest integrated gate result: P0 = 0, P1 = 0.

## PR Notes

- PR title: `feat: add Prometheus dashboard example`
- Body must mention `Closes #230` and `Closes #145`.
- Merge is not performed by the agent unless the user asks after CI passes.
