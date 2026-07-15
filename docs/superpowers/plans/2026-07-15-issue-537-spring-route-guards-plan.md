# Issue #537 Spring Route Guards Implementation Plan

## Goal

Deliver opt-in, read-only Spring MVC and WebFlux route guards that permit a
handler only when the configured authority establishes local leadership. Keep
the built-in `LeaderSlot` state authority and the user-provided authority mode
strictly exclusive, with startup failure for every mixed, missing, or ambiguous
configuration.

## Architecture

Add a small framework-neutral route authority layer in `leader-spring-boot`, a
disabled-by-default auto-configuration, and optional MVC/WebFlux helper
factories. `STATE` mode selects one `LeaderElector` and compares the guarded
slot's process-incarnation leader ID with one state snapshot, but only after the
elector declares audit-identity state capability. `CUSTOM` mode delegates to exactly one
application bean. Both web adapters share the decision and rejection contracts;
the WebFlux adapter defers and offloads synchronous evaluation.

## Acceptance traceability

| Design acceptance | Plan task | Proof |
|---|---|---|
| Disabled default does not change existing applications | Task 1 | `ApplicationContextRunner` disabled and multi-backend tests |
| `STATE` and `CUSTOM` are exclusive | Tasks 1-2 | mixed/missing/ambiguous startup failure tests |
| Default decision compares slot identity to one state read | Task 2 | authority state matrix and interaction tests |
| Unsupported state electors fail before traffic | Tasks 1-2 | explicit capability, wrapper, and real Local backend tests |
| No acquire, extend, release, or watchdog calls | Task 2 | strict mock interaction assertions |
| MVC rejects before one handler invocation | Task 3 | MockMvc contract tests |
| WebFlux is deferred, offloaded, and cancellation-safe | Task 4 | WebTestClient/Reactor/coroutine tests |
| Equivalent bounded status and empty-body response | Tasks 3-4 | shared status parameter tests |
| English/Korean docs and exclusive-mode diagram | Task 5 | parity review and visual validation |
| Repository quality and delivery gates | Task 6 | module tests, AOT, detekt, review, PR CI |

## Files

Expected source changes:

- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteAuthority.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteDecision.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/StateLeaderRouteAuthority.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteGuardConfigurationException.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteGuardAutoConfiguration.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/mvc/LeaderMvcRouteGuardFactory.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/webflux/LeaderWebFluxRouteGuardFactory.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionState.kt` and capability-preserving core decorators
- Modify: audit-state-capable Local, Consul, DynamoDB, Kubernetes Lease, and Micrometer implementations
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderProperties.kt`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `leader-spring-boot/build.gradle.kts`
- Modify: `gradle/libs.versions.toml` only if reusable Spring web aliases are required.

Expected tests and documentation:

- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/StateLeaderRouteAuthorityTest.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteGuardAutoConfigurationTest.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/mvc/LeaderMvcRouteGuardTest.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/webflux/LeaderWebFluxRouteGuardTest.kt`
- Create: `leader-spring-boot/src/test/java/io/bluetape4k/leader/spring/route/NullLeaderRouteAuthority.java`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/LeaderPropertiesBindingTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`
- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Modify: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg`
- Modify: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.png`
- Create: `docs/review/2026-07-15-issue-537-spring-route-guards-review.md`
- Create: `docs/lessons/2026-07-15-issue-537-spring-route-guards.md`

## Task 1: Lock property and configuration exclusivity contracts

Complexity: high. This is the startup compatibility and configuration-safety
gate; complete it before route adapters.

- [ ] Add binding RED tests for `enabled`, `authority-mode`, `elector-bean`, and
  `rejection-status`, including default values.
- [ ] Add `ApplicationContextRunner` RED tests proving:
  - disabled default creates no authority/helper and ignores unrelated multiple
    elector or custom-authority beans;
  - enabled `STATE` creates only the built-in authority;
  - `STATE` plus any custom authority fails with
    `LEADER_ROUTE_AUTHORITY_MIXED`;
  - `STATE` resolves explicit, unique, and primary electors;
  - missing or ambiguous state electors fail with stable codes;
  - an elector or wrapper without audit-identity state capability fails with
    `LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED`;
  - enabled `CUSTOM` requires exactly one user authority;
  - `CUSTOM` plus `elector-bean` fails as mixed configuration;
  - unsupported rejection status fails binding/startup validation.
- [ ] Run the focused tests and record RED before source changes.
- [ ] Implement the property model, disabled-by-default auto-configuration,
  elector resolution, and configuration validator with stable sanitized errors.
- [ ] Register configuration metadata and the auto-configuration import.
- [ ] Rerun focused tests to GREEN.

Startup validation must occur before guarded traffic is served. Do not use
`@ConditionalOnMissingBean` to choose between built-in and user authority modes.

## Task 2: Implement the authority decision layer test-first

Complexity: medium. Depends on Task 1 GREEN.

- [ ] Add RED tests for matching, empty, missing audit identity, mismatched audit
  identity, and state-read failure outcomes.
- [ ] Prove the real Local backend exposes the matching slot identity only while
  the slot-aware election is held.
- [ ] Prove capable listener decorators preserve the full `LeaderSlot` audit
  identity across synchronous, asynchronous, suspend, and result APIs.
- [ ] Assert every backend marked audit-state-capable writes the slot identity
  and reads it back unchanged from `state()`.
- [ ] Assert exactly one `state(slot.lockName)` call per evaluation.
- [ ] Assert the passive path never calls run/acquire, async run, extension,
  release, or watchdog APIs.
- [ ] Implement `LeaderRouteDecision`, `LeaderRouteAuthority`, and the minimal
  `StateLeaderRouteAuthority`.
- [ ] Normalize ordinary lookup failures to `Unavailable`; preserve fatal JVM
  errors and cancellation/interrupt semantics where applicable.
- [ ] Rerun focused authority tests to GREEN.

Rollback point: disabling `bluetape4k.leader.route-guard.enabled` removes the
entire runtime surface without changing leader-election behavior.

## Task 3: Implement the MVC adapter test-first

Complexity: medium. Depends on Tasks 1-2 GREEN.

- [ ] Add MockMvc RED tests for `Allowed`, `NotLeader`, `Unavailable`, authority
  exception, Java `null`, cancellation/interruption, each supported rejection status, empty response body, sanitized
  headers, and exactly-once handler invocation.
- [ ] Add route-registration tests proving unguarded routes remain unaffected.
- [ ] Implement the MVC helper factory and route-scoped interceptor/filter using
  optional Spring MVC types.
- [ ] Ensure a rejected decision returns before handler invocation and does not
  duplicate existing observation/interceptor behavior.
- [ ] Rerun MVC tests to GREEN.

## Task 4: Implement the WebFlux adapter test-first

Complexity: high. Depends on Tasks 1-2 GREEN and mirrors Task 3 semantics.

- [ ] Add WebTestClient RED tests for the full allowed/rejected/status/sanitized
  response matrix, including a Java `null` decision.
- [ ] Add scheduler evidence proving synchronous authority evaluation does not
  run on the event-loop thread.
- [ ] Add Reactor cancellation tests before evaluation, before handler
  subscription, and after handler subscription.
- [ ] Add coroutine handler cancellation coverage with no rejected handler work.
- [ ] Implement a deferred, route-scoped WebFlux filter that offloads authority
  evaluation and subscribes to the protected handler only for `Allowed`.
- [ ] Rerun WebFlux tests to GREEN and compare behavior with MVC assertions.

Do not cache decisions across requests. A state snapshot is already best-effort;
caching would widen the stale-leadership window.

## Task 5: Document and diagram the final contract

Complexity: medium. Depends on final public names from Tasks 1-4.

- [ ] Update English and Korean README files with opt-in configuration, shared
  `LeaderSlot`, default state mode, custom mode, startup errors, safe statuses,
  and best-effort/fail-closed limitations.
- [ ] Update `leader-spring-boot-architecture-01.svg` so built-in state authority
  and custom SPI appear as exclusive inputs to shared MVC/WebFlux adapters.
- [ ] Render the 2x PNG from the SVG using the repository diagram workflow.
- [ ] Perform full-size visual review for text fit, connector visibility,
  arrowheads, and dark-style consistency.
- [ ] Validate README language links/parity and `git diff --check`.

## Task 6: Verification, review, and PR delivery

Complexity: high. Depends on Tasks 1-5 GREEN.

- [ ] Run focused route authority/configuration/MVC/WebFlux tests from a clean
  test output.
- [ ] Run the complete `:bluetape4k-leader-spring-boot:test` suite.
- [ ] Run `:bluetape4k-leader-spring-boot:aotTest`, module build, and `detekt`.
- [ ] Run applicable repository static/docs/diagram checks and `git diff --check`.
- [ ] Write the lesson and six-perspective review; P0/P1 must be zero.
- [ ] Run a bounded Codex review against `develop`, repair HIGH/CRITICAL findings,
  and rerun affected checks.
- [ ] Commit with Lore trailers and push
  `feature/issue-537-spring-route-guards`.
- [ ] Open a PR to `develop`, assign `debop`, mirror #537 labels/milestone, and
  end the body with `## DoD Status`.
- [ ] Wait for exact-head CI and review/thread convergence, then report the exact
  PR/head as merge-ready and stop for fresh merge approval.

## Risk prediction and rerun points

| Risk | Signal | Mitigation / rerun point |
|---|---|---|
| Existing multi-backend apps fail despite no route guards | Disabled context validates or creates route beans | Keep `enabled=false`; return to Task 1 |
| Custom bean silently overrides built-in mode | Context starts in `STATE` with user authority | Stable mixed-config failure; return to Task 1 |
| Multi-backend state mode selects the wrong elector | Unnamed ambiguous context starts or explicit name ignored | Resolver matrix tests; return to Task 1 |
| Unsupported backend rejects every route forever | Empty-fallback elector starts in STATE | Explicit delegated capability and stable startup failure; return to Tasks 1-2 |
| Reused audit ID admits a restarted process | Fixed node ID matches a predecessor's stale lease | Require process-incarnation-unique IDs in KDoc and both READMEs; return to Task 5 |
| State check permits another node | Occupied state alone returns `Allowed` | Require audit ID equality; return to Task 2 |
| Passive guard changes lease state | Mock observes run/extend/release call | Interaction prohibition tests; return to Task 2 |
| WebFlux blocks event loop | Evaluation thread has event-loop marker | Deferred offload test; return to Task 4 |
| Cancellation invokes protected work | Handler counter increments after rejected/cancelled path | Cancellation matrix; return to Task 4 |
| Response leaks topology or backend failure | Body/header contains slot, ID, bean, exception, or location | Empty-body parity tests; return to Tasks 3-4 |
| Diagram implies both authorities run together | Both paths converge without exclusive-mode label | Diagram review; return to Task 5 |

## Plan review convergence

| Priority | Perspective | Finding | Repair included in plan |
|---|---|---|---|
| P1 | Compatibility | Eager route auto-configuration could break existing multi-backend applications. | Added disabled-by-default activation and disabled-context regression tests. |
| P1 | Configuration | Silent `@ConditionalOnMissingBean` replacement violates the approved exclusive-mode contract. | Added explicit mode validation and stable mixed/missing/ambiguous failures. |
| P1 | Backend selection | `LeaderSlot` identifies a lock/node but not which Spring elector bean owns the backend. | Added `elector-bean` plus explicit/unique/primary resolver tests. |
| P1 | State capability | Several electors inherit an always-empty state fallback, including through decorators. | Added an explicit delegated capability and startup rejection for unsupported electors. |
| P1 | Identity freshness | A fixed leader ID can match a predecessor's stale lease after restart. | Required one ID per live process incarnation and the same slot within that process. |
| P1 | Reactive safety | `LeaderElectionState.state` is synchronous and could block an event loop. | Added deferred offload implementation and scheduler evidence. |
| P2 | Security | Error bodies could disclose leader or backend information. | Standardized an empty body and sanitized error/configuration tests. |
| P2 | Correctness | Snapshot caching could extend stale leadership decisions. | Explicitly prohibited cross-request decision caching. |
| P2 | Operations | Configuration exceptions need actionable but safe diagnosis. | Added stable error codes and sanitized bean-name-only detail rules. |

All P1 findings were repaired in this revision. Latest integrated plan result:
P0=0, P1=0.
