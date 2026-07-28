# Issues 531 and 536 Spring Operations Design

## 한국어 해설

이 문서는 `Issues 531 and 536 Spring Operations Design`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Context

Issues #531 and #536 were moved into milestone 0.5.0 after separating the parts that need broader contracts:

- backend connectivity and capability diagnostics remain in #533;
- recent acquisition-failure windows are tracked in #602;
- YAML-only policy wrapping for existing scheduled methods is tracked in #603;
- MVC/WebFlux route helpers remain in Backlog issue #537.

The implementation must reuse the current Spring Boot observability registry, `LeaderElector.state`, Spring scheduling, and `LeaderElectionAspect`. It must not enumerate backend locks, mutate election state from health checks, or create a second scheduling engine.

## #531: Known-Lock Readiness

### Public contract

Add an opt-in `LeaderElectionReadinessHealthIndicator` and nested observability health properties:

```yaml
bluetape4k:
  leader:
    observability:
      health:
        enabled: true
        lease-warning-threshold: 10s
```

The indicator reads only the sorted names already held by `LeaderElectionStatusRegistry`. For each name it calls the existing best-effort `LeaderElector.state(name)` method once.

The result is:

- `UP` when every state read succeeds and no occupied lease expires within the warning threshold;
- `OUT_OF_SERVICE` when an occupied lease is expired or expires at/before `now + threshold`;
- `DOWN` when any known-lock state read throws.

Details include `knownLocks`, `occupiedLocks`, `unknownLeaseExpiry`, `expiringLeases`, `expiringLockNames`, and `failedLockNames`. Empty registries are `UP` with zero counts. Missing lease expiry is reported as unknown rather than treated as unhealthy.

The contributor is disabled by default and lives under the existing observability parent switch. Applications explicitly add `leaderElectionReadiness` to the Spring Boot readiness health group.

### Cost and disclosure contract

The check is intentionally linear: one sequential state read and one small result entry per JVM-known lock. It is not a hot-path API, but a large or dynamically growing registry increases health latency and backend load. Operators should seed a bounded static set for readiness and choose probe intervals accordingly; applications with unbounded dynamic lock names should leave the contributor disabled. No cache is added because stale lease risk is worse than avoiding a bounded read.

`expiringLockNames` and `failedLockNames` expose raw registered names in health details. Applications must apply Spring Boot Actuator access and `show-details` policy as they would for any operational identifier. Exception types, messages, endpoints, and credentials are never copied into details.

### Safety boundary

The health result is diagnostic and JVM-local. It is not an ownership oracle and must never be consulted to decide whether a task may run. General backend connectivity/capability checks remain #533, while time-windowed failure history remains #602.

### Failure modes

- A state read throws: continue checking remaining names, report only the failed name, and finish `DOWN` without exposing the exception.
- An occupied backend cannot report expiry: increment `unknownLeaseExpiry` and remain healthy unless another risk exists.
- The registry is empty: return `UP` with zero counts; this means "nothing configured or observed", not cluster-wide health.
- A negative warning threshold is configured: fail property construction/startup instead of silently changing semantics.
- Concurrent health calls or registry updates: each call uses the registry's stable sorted snapshot and local accumulators, so results may differ between calls but cannot corrupt shared state.

## #536: Leader-Aware Scheduled Annotation

### Public contract

Add `@LeaderScheduled`, a runtime function annotation meta-annotated with both Spring `@Scheduled` and core `@LeaderElection`.

It exposes aliases for all standard Spring scheduling attributes (`cron`, `zone`, fixed rate/delay, initial delay, `timeUnit`, and `scheduler`) and all `@LeaderElection` attributes. The string scheduling variants retain Spring property-placeholder support.

`LeaderElectionAspect` expands its AspectJ pointcut to include `@LeaderScheduled`. Metadata resolution continues to synthesize the underlying `@LeaderElection`, so lock names, factory selection, failure policy, recorders, and skip-on-contention behavior remain on the existing path. Spring continues to own task discovery, registration, observation, and trigger timing.

Spring's existing scheduled-method constraints still apply: scheduling must be enabled, methods must use a valid Spring scheduled signature, and exactly one cron/fixed-rate/fixed-delay trigger family is selected. Leader annotation validation still rejects invalid SpEL and other existing AOP footguns. The annotation does not create or register tasks itself.

The implementation dogfoods `@LeaderScheduled` on the blocking history-retention job so the normal main-source AspectJ compile step verifies the composed pointcut. Existing methods using separate `@Scheduled` and `@LeaderElection` remain source and binary compatible.

### Alternatives rejected

- A new scheduler or dynamic task registry duplicates Spring scheduling and creates a second observation boundary.
- YAML-only automatic wrapping requires selector, precedence, and validation semantics and is therefore #603.
- Requiring users to define their own composed annotation remains supported, but it does not provide a discoverable first-party scheduling surface.

### Failure modes

- Contention returns the existing skipped result and never invokes the scheduled body.
- Backend failures follow the aliased `failureMode`; no scheduling-specific exception policy is introduced.
- An invalid or conflicting Spring schedule fails through Spring's existing scheduled-task validation.
- Invalid leader SpEL or unsafe method declarations fail through the current leader annotation validator.
- If a consumer cannot adopt the new annotation, replacing it with equivalent separate `@Scheduled` and `@LeaderElection` declarations is the rollback path.

## Documentation

The versioned manual remains pinned to release 0.4.0, so 0.5.0 API claims must not be added there before its manifest advances. The unreleased module README/KDoc will document the new surfaces in English and Korean; the release-manual update follows the normal manifest bump.

## Acceptance

- Deterministic readiness unit tests cover `UP`, `OUT_OF_SERVICE`, `DOWN`, empty, and unknown-expiry behavior.
- Context tests prove the readiness bean is opt-in and the threshold binds.
- Merged annotation tests prove both Spring and leader aliases.
- Aspect tests prove the composed annotation invokes the current election path and skips contention without invoking the body.
- Targeted tests, full Spring module tests, Detekt, diff checks, and bounded code review pass.

## Compatibility and migration

Both additions are opt-in and additive. Existing health contributors, Actuator endpoint IDs, annotations, factories, and scheduling behavior are unchanged. No dependency, module, BOM, CI matrix, or publishing registration changes. The readiness rollback is disabling `bluetape4k.leader.observability.health.enabled`; the scheduling rollback is expanding `@LeaderScheduled` into the two existing annotations.

## Review convergence

External read-only Codex review processes were attempted twice but exceeded the bounded review window because repository startup hooks dominated their execution. Per the workflow fallback, the main session applied all six lenses against the exact spec, plan, and local source anchors.

| Priority | Lens | Finding | Resolution |
|---|---|---|---|
| P2 | performance | Health cost grows linearly with JVM-known lock names and backend state latency. | Added explicit cost model, bounded-static-set guidance, and call-count proof requirement. |
| P2 | stability | Failure, concurrency, CTW, and rollback behavior were implicit. | Added five readiness and five scheduling failure modes plus dogfood and rollback contracts. |
| P2 | security | Raw lock-name details and SpEL trust boundaries were not called out. | Added Actuator detail-access warning, no-exception-disclosure rule, and reuse of existing SpEL controls. |
| P2 | operator/Ops | Empty registry and unknown expiry could be misread as cluster health. | Defined JVM-local semantics and explicit detail/status interpretation. |
| P2 | developer/API | Compatibility and Spring scheduled-method constraints were incomplete. | Added additive compatibility, existing validation ownership, and rollback mapping. |
| P2 | user/caller | Scheduling enablement and misuse boundaries needed documentation. | Added the Spring constraint contract and README acceptance requirement. |

Latest integrated result: P0=0, P1=0. All P2 findings are repaired in this revision.
