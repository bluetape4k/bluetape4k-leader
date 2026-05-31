# Issue 226 Leader Observability Design

## Context

Issue #226 asks for Spring Boot observability around leader election:

- expose a `LeaderElectionEventPublisher` bean when possible;
- add an opt-in Actuator endpoint at `/actuator/leaderElection`;
- add a Ktor equivalent route at `/management/leaderElection`;
- return per-lock leadership status JSON.

This branch is stacked on #224 because blocking `ListeningLeaderElector` and `ListeningLeaderGroupElector` implement `LeaderElectionEventPublisher` there.

## Evidence

- Spring Boot 4 official docs confirm custom Actuator endpoints use `@Endpoint(id = "...")` and `@ReadOperation`, and web exposure is controlled separately by `management.endpoints.web.exposure.include`. Only `health` is web-exposed by default.
- Ktor official docs confirm `routing { get { call.respond(...) } }` and `testApplication` as the route/test surface.
- Current repo has no global lock-name registry. `LeaderElectionState.state(lockName)` can query a known name, but cannot enumerate names.
- Current repo has listener/event decorators, but creating a separate wrapper bean cannot intercept calls made through an already-injected original `LeaderElector`.
- `gnoq` was unavailable in this shell, so memory lookup fell back to `rg` over repo docs and source.

## Design

### Spring Boot

Add `LeaderElectionObservabilityAutoConfiguration` after the core/aop auto-config phases.

It registers:

- `LeaderElectionStatusRegistry`
  - stores known lock names in a thread-safe sorted set;
  - seeded from `bluetape4k.leader.observability.lock-names`;
  - implements `LeaderElectionListener` so listener-aware electors can add names from `onElected`, `onSkipped`, and `onRevoked`.
- `LeaderElectionEventPublisher` fallback bean
  - if no publisher bean exists, expose a publisher-only listener adapter;
  - the adapter emits events observed through `LeaderElectionListenerRegistry` beans and never becomes a `LeaderElector` candidate.
- `LeaderElectionListenerRegistry` registrar
  - attaches the status registry to all listener-aware leader beans after singleton initialization.

Add `LeaderElectionActuatorAutoConfiguration` after observability:

- guarded by Actuator classes using `@ConditionalOnClass(name = [...])`;
- endpoint bean is disabled by default with `management.endpoint.leaderElection.enabled=true`;
- `@Endpoint(id = "leaderElection")`;
- `@ReadOperation` returns `{ "locks": [...] }`;
- each lock status reads from `LeaderElector.state(lockName)`;
- use `auditLeaderId`, not deprecated `leaderId`.

HTTP exposure remains the user/application responsibility:

```yaml
management:
  endpoint:
    leaderElection:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,leaderElection
```

### Ktor

Extend `LeaderElectionPluginConfig` with:

- `managementRouteEnabled: Boolean = false`;
- `managementRoutePath: String = "/management/leaderElection"`;
- `managementLockNames(vararg lockNames: String)`.

When enabled, the plugin installs a `GET` route that returns JSON with the same lock fields. `leaderScheduled()` records its lock name into the plugin registry when the plugin is installed.

The Ktor response is emitted as JSON text to avoid forcing a new serialization dependency on users.

## Non-Goals

- Do not replace all existing `LeaderElector` beans with listener wrappers. That can break concrete-type injection and existing tests.
- Do not promise complete lock enumeration for dynamic lock names that were never configured or observed.
- Do not expose group elector status in this issue. Group state has different response semantics and follows #238.

## Acceptance Mapping

- `LeaderElectionEventPublisher` bean auto-registration: supported as a fallback publisher-only listener adapter.
- `/actuator/leaderElection`: supported when Actuator is present, endpoint is enabled, and web exposure includes it.
- disabled by default: endpoint property has no `matchIfMissing`.
- Ktor management route: supported by plugin opt-in flag.
- response shape test: Spring endpoint and Ktor route tests cover the JSON/status shape.

## Risks

- The fallback publisher only observes listener callbacks. It does not observe calls made through non-listener-aware elector beans.
- Endpoint lock-name completeness depends on static registration or observed listener/Ktor scheduled usage.
- Spring endpoint ID uses the requested camel-case `leaderElection`; tests must verify Boot accepts it.
