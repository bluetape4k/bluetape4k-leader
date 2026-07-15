# Issue 537 Spring Route Guards Review

## Scope and review basis

- Branch: `feature/issue-537-spring-route-guards`
- Base: `develop` at `ad024ca9`
- Approved artifacts:
  - `docs/superpowers/specs/2026-07-15-issue-537-spring-route-guards-design.md`
  - `docs/superpowers/plans/2026-07-15-issue-537-spring-route-guards-plan.md`
- Primary module: `leader-spring-boot`
- Supporting contract changes: Core audit-state capability plus Local, Consul,
  DynamoDB, Kubernetes Lease, listener, tenant-scoped, and Micrometer support
- Explicit exclusions: redirect/identity metadata in #606 and request-path
  lease acquisition in #607

The final implementation keeps `STATE` and `CUSTOM` as separate authority
models. Mixed, missing, ambiguous, and audit-state-unsupported selections fail
startup with stable codes. Route evaluation is passive, fail-closed, empty-body,
identity-free, and disabled by default.

## Performance and stability scan

| Priority | Surface | Lens | Finding | Resolution / evidence |
|---|---|---|---|---|
| P2 | `StateLeaderRouteAuthority` | performance | `STATE` adds one backend state lookup per guarded request. | Exactly one read is enforced by interaction tests; no cache, retry, acquisition, extension, release, or background worker was added. README guidance requires route-scoped use and records the best-effort cost. |
| P2 | WebFlux adapter | performance/stability | A synchronous authority could block an event-loop thread. | Evaluation is deferred until subscription and offloaded to `boundedElastic`; tests prove handler subscription occurs only after `Allowed`. |
| P1, repaired | state capability decorators | correctness/stability | A listener wrapper could advertise audit-state capability while interface bridge defaults discarded `LeaderSlot.leaderId`. | Slot-aware sync, async, suspend, and result overloads now delegate the full slot. Local async and decorated regression tests read the exact audit identity back from state. |
| P1, repaired | MVC/WebFlux adapters | cancellation | Normalizing every throwable would convert cancellation/interruption into a rejection. | Cancellation is rethrown, interruption restores the thread flag, and only ordinary failures become `Unavailable`; pre-evaluation, during-evaluation, and post-subscription tests pass. |

No new retry loop, shared cache, unbounded buffer, lease mutation, watchdog, or
request-owned resource was added. Final performance/stability result: P0=0,
P1=0.

## Spec and plan verification

| Requirement | Implementation and proof | Status |
|---|---|---|
| Disabled default | Conditional auto-configuration and disabled-context tests | PASS |
| Strict `STATE` / `CUSTOM` separation | Explicit mode selector and mixed/missing/ambiguous startup matrix | PASS |
| Audit-state capability | Conservative Core default, capable backend declarations, preserving decorators, startup and constructor invariants | PASS |
| Passive default authority | One `state(lockName)` read, exact audit ID comparison, strict mock verification | PASS |
| MVC semantics | Route-scoped interceptor, one handler invocation only for `Allowed`, empty bounded status response | PASS |
| WebFlux semantics | Deferred/offloaded filter, no rejected handler subscription, cancellation preservation | PASS |
| Java interoperability | Java authority returning `null` is normalized to `Unavailable` and rejected | PASS |
| Configuration selection | Explicit, unique, and primary elector/authority selection tests prove which candidate is used | PASS |
| Documentation parity | English/Korean README sections cover identical modes, errors, statuses, capability limits, process-incarnation identity, and caveats | PASS |
| Diagram | Existing Spring architecture SVG and 2x PNG show exclusive authority inputs and shared route adapters | PASS |
| Scope discipline | Redirect and request-path acquisition remain in #606/#607; no module, BOM, publishing, or workflow change | PASS |

## Independent review convergence

The first independent code review requested changes for Java-null handling,
unsupported state fallbacks, explicit bean type errors, Servlet classpath
conditioning, selection-proof tests, and cancellation/interruption coverage.
The first architecture pass additionally blocked capability claims that were
not backed by an explicit state contract, warned about process identity reuse,
and found the built-in bean-name collision path. Those findings were repaired.

A later architecture delta review found one remaining P1: listener decorators
delegated the capability flag but not slot-aware execution methods. Regression
tests first reproduced the default node identity in sync, async, and suspend
state. The listener and Local async paths were repaired, and public
`StateLeaderRouteAuthority` construction was made capability-safe.

Final independent results:

| Lane | Verdict | P0 | P1 | P2 |
|---|---|---:|---:|---:|
| Code review | APPROVE | 0 | 0 | 0 |
| Architecture review | CLEAR | 0 | 0 | 0 |

Both final passes were read-only. They independently confirmed slot identity,
async result classification, notification cardinality, cancellation behavior,
constructor enforcement, and public API compatibility.

## Six-lens final review

| Lens | P0 | P1 | P2 | Integrated result |
|---|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | One uncached state read is explicit and tested; WebFlux offloads synchronous evaluation. |
| Stability | 0 | 0 | 0 | Invalid configurations fail at startup; cancellation/interruption and listener identity are preserved. |
| Security | 0 | 0 | 0 | Rejections expose no leader ID, location, exception, or body; occupancy alone never authorizes. |
| Operator/Ops | 0 | 0 | 0 | Stable error codes, safe status set, supported backend list, and rollback-by-disable are documented. |
| Developer/API | 0 | 0 | 0 | APIs are additive; STATE is the default, CUSTOM is an explicit SPI, and mixing is an error. |
| User/caller | 0 | 0 | 0 | MVC/WebFlux usage, route scope, process-incarnation identity, and non-atomic caveats are aligned in both locales. |

## Diagram evidence ledger

| Check | Result |
|---|---|
| SVG XML validation | PASS |
| Connector audit | PASS: markers=4, connectors=5, cards=17, intrusions=0, crossings=0 |
| Geometry audit | PASS: `geometry_failures=0` |
| Endpoint audit | PASS: 1 file |
| Mixed-corner audit | PASS: failures=0; this SVG uses no path-level `Q` bends |
| Raster pair | PASS: SVG 1320x1360, PNG 2640x2720, 2x, sRGB |
| Full-size visual review | PASS: text fits, all arrowheads/connectors are visible, and the authority band remains distinct from the woven execution path |

## Fresh verification

| Command / gate | Result |
|---|---|
| Core full test | PASS, 713 tests |
| Consul full test | PASS, 64 tests |
| DynamoDB full test | PASS, 30 tests |
| Kubernetes unit test | PASS, 13 tests |
| Kubernetes K3s integration test | PASS, 21 tests |
| Micrometer full test | PASS, 76 tests |
| Spring Boot full test | PASS, 422 tests |
| Spring Boot AOT test | PASS, 6 tests |
| Spring Boot module build | PASS |
| Root Detekt command | PASS command; root task reports `NO-SOURCE` and is not claimed as Kotlin source coverage |
| Diagram audits and full-size review | PASS |
| `git diff --check` | PASS |

One earlier Core run and one earlier Spring run completed all test cases but
Gradle then lost `in-progress-results-generic.bin`. Neither run was accepted as
verification evidence; isolated reruns with observed exit code 0 are the results
listed above.

`dokkaGenerateHtml` remains unavailable because the repository baseline fails
with `Unexpected classifier: "#"` and pre-existing unresolved KDoc warnings. The
same failure was reproduced on clean `develop`; compilation, tests, AOT, and the
module build are the accepted API-documentation checks for this change.

Final review verdict: `PASS`; P0=0, P1=0, P2=0.
