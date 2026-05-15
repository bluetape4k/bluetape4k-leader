# Issue 226 Leader Observability

## Context

Spring Boot and Ktor users needed opt-in status endpoints for known leader election locks, plus a Spring `LeaderElectionEventPublisher` bean surface.

## Decision

Add a JVM-local lock-name registry and endpoint layer instead of trying to enumerate backend locks. Static lock names seed the registry, and listener-aware electors or Ktor `leaderScheduled()` calls can add observed names.

For Spring, expose a publisher-only listener adapter when no `LeaderElectionEventPublisher` bean exists. Do not expose a `ListeningLeaderElector` fallback as an autowire candidate, because that makes `LeaderElector` injection ambiguous and can break existing auto-configuration tests.

## Outcome

- Spring adds `LeaderElectionObservabilityAutoConfiguration` and opt-in `LeaderElectionActuatorAutoConfiguration`.
- Ktor adds opt-in `GET /management/leaderElection`.
- Both surfaces return per-lock single-leader status using `auditLeaderId` and `leaseUntil`.

## Verification

- `./gradlew :leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' --no-configuration-cache --console=plain`
- `./gradlew :leader-ktor:test --tests 'io.bluetape4k.leader.ktor.LeaderElectionManagementRouteTest' --no-configuration-cache --console=plain`
- `./gradlew :leader-spring-boot:test :leader-ktor:test --no-configuration-cache --console=plain`
- Result: `leader-ktor` 16 tests and `leader-spring-boot` 293 tests passing. The full run printed pre-existing shutdown/logging thread warnings but completed successfully.

## Future Notes

Keep lock enumeration explicit or observed. If a future issue needs complete backend lock discovery, add a backend-specific listing contract instead of inferring names from state queries.
