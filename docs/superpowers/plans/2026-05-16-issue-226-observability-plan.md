# Issue 226 Leader Observability Plan

## Scope

Implement the Spring Boot and Ktor observability surfaces defined in the spec:

- Spring status registry, fallback publisher adapter, and Actuator endpoint.
- Ktor opt-in management route.
- Tests, README updates, lesson, PR.

## Tasks

1. Spring properties and registry
   - Add `LeaderObservabilityProperties`.
   - Add `LeaderProperties.observability`.
   - Add `LeaderElectionStatusRegistry`.
   - Add configuration metadata for new properties.

2. Spring auto-config
   - Add `LeaderElectionObservabilityAutoConfiguration`.
   - Add `LeaderElectionActuatorAutoConfiguration`.
   - Register both in `AutoConfiguration.imports` after existing leader/aop phases.
   - Guard Actuator types with `@ConditionalOnClass(name = [...])`.

3. Spring endpoint
   - Add `LeaderElectionStatusEndpoint`.
   - Return serializable DTOs with `locks`, `name`, `status`, `leaderId`, and `leaseExpiry`.
   - Use `LeaderLease.auditLeaderId`.

4. Ktor management route
   - Add an internal lock registry to `LeaderElectionPluginConfig`.
   - Add route opt-in properties and lock registration helper.
   - Install `GET /management/leaderElection` only when enabled.
   - Record `leaderScheduled()` lock names when the plugin is installed.
   - Return JSON text without adding serialization dependencies.

5. Tests
   - Spring ApplicationContextRunner tests:
     - registry seeded from properties;
     - fallback publisher adapter registered;
     - endpoint disabled by default;
     - endpoint registered when `management.endpoint.leaderElection.enabled=true`;
     - endpoint response shape for a known lock.
   - Ktor tests:
     - route disabled by default;
     - route returns configured lock status when enabled;
     - `leaderScheduled()` records lock names.

6. Docs and durable capture
   - Update `README.md` and `README.ko.md` for Spring/Ktor observability.
   - Add `docs/lessons/2026-05-16-issue-226-observability.md`.

7. Verification
   - `./gradlew :leader-spring-boot:test --tests '*LeaderElectionObservability*' --tests '*LeaderElectionActuator*' --no-configuration-cache --console=plain`
   - `./gradlew :leader-ktor:test --tests '*LeaderElectionManagement*' --no-configuration-cache --console=plain`
   - Broaden to `:leader-spring-boot:test :leader-ktor:test` if targeted tests pass quickly.
   - `git diff --check`.

## Review Notes

- P0/P1 design findings after local review: none.
- Advisor gap: local Claude CLI hung during #238 review. For #226, use local review first and attempt advisor only after implementation if the CLI responds within a bounded timeout.
