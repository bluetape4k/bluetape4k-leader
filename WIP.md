# WIP - bluetape4k-leader

Snapshot: 2026-07-03 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 12 Backlog issues after closing the 0.5.0 epic.
Milestone `0.5.0` is complete except for final epic closure.

## Current Direction

The `0.5.0` milestone is capped and complete. It closed the operational
readiness lane around observability, metric cardinality, Prometheus runbooks,
Spring Boot metadata, startup diagnostics, correctness fixes, and benchmark
evidence.

Do not expand `0.5.0` with additional Backlog work unless a release-blocking
defect is found. The remaining assigned issues should be scheduled into the next
minor line after the 0.5.0 release closeout.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#532](https://github.com/bluetape4k/bluetape4k-leader/issues/532) opt-in leader management actions for known locks | Backlog | Security-sensitive management action surface; keep separate from 0.5.0 closeout. |
| P1 | [#542](https://github.com/bluetape4k/bluetape4k-leader/issues/542) route-scoped Ktor leader guard DSL | Backlog | Security-sensitive route guard API; needs design review before implementation. |
| P1 | [#537](https://github.com/bluetape4k/bluetape4k-leader/issues/537) MVC/WebFlux leader-gated route helpers | Backlog | Spring route helper surface; pair with security and API ergonomics review. |
| P2 | [#531](https://github.com/bluetape4k/bluetape4k-leader/issues/531) readiness and lease-risk health indicators | Backlog | Spring health surface for a later operational-readiness increment. |
| P2 | [#533](https://github.com/bluetape4k/bluetape4k-leader/issues/533) backend health and capability diagnostics SPI | Backlog | Cross-backend diagnostics design candidate. |
| P2 | [#535](https://github.com/bluetape4k/bluetape4k-leader/issues/535) pluggable audit export adapters | Backlog | Audit/export integration lane. |
| P2 | [#536](https://github.com/bluetape4k/bluetape4k-leader/issues/536) leader-aware scheduled task adapter | Backlog | Spring scheduling convenience API. |
| P2 | [#539](https://github.com/bluetape4k/bluetape4k-leader/issues/539) SSE and WebSocket leader event streams | Backlog | Ktor streaming integration. |
| P2 | [#540](https://github.com/bluetape4k/bluetape4k-leader/issues/540) Ktor StatusPages and structured error integration | Backlog | Ktor error contract integration. |
| P2 | [#541](https://github.com/bluetape4k/bluetape4k-leader/issues/541) Ktor lifecycle and graceful shutdown hooks | Backlog | Ktor plugin lifecycle hardening. |
| P2 | [#559](https://github.com/bluetape4k/bluetape4k-leader/issues/559) lease-extension observation hook | Backlog | Observation follow-up for renewal paths. |
| P3 | [#463](https://github.com/bluetape4k/bluetape4k-leader/issues/463) strategic group election API design | Backlog | Design-only API exploration. |

## Open PRs

No open PRs were present before this closeout documentation branch.

## Recently Completed

- [#529](https://github.com/bluetape4k/bluetape4k-leader/issues/529) added the Micrometer Observation and OpenTelemetry bridge.
- [#530](https://github.com/bluetape4k/bluetape4k-leader/issues/530) added metric tag cardinality controls.
- [#534](https://github.com/bluetape4k/bluetape4k-leader/issues/534) added Prometheus alert rules and leader runbooks.
- [#538](https://github.com/bluetape4k/bluetape4k-leader/issues/538) added Spring Boot configuration metadata and startup diagnostics.
- [#561](https://github.com/bluetape4k/bluetape4k-leader/issues/561) is the final 0.5.0 epic and should close after this documentation refresh is merged.

## Refresh Notes

- Verified with `gh` on 2026-07-03 KST.
- Milestone `0.5.0` has no open non-epic issues.
- `CHANGELOG.md` now records the 0.5.0 observability, diagnostics,
  correctness, and benchmark closeout.
- Keep remaining Backlog issues out of 0.5.0 unless a release-blocking defect is
  discovered.
