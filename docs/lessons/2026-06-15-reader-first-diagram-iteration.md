# Reader-First Diagram Iteration

## Context

The README diagram refresh moved from bulk regeneration toward one module at a
time. User review caught architecture diagrams that still read like runtime
flows, sequence frames that overlapped cleanup branches, and a render defect
where the SVG canvas height no longer matched the enlarged viewBox.

## Decision

Treat every module as a small reader-question pass:

- Architecture diagrams explain static ownership, component boundaries, and
  dependencies first. Put runtime ordering in sequence diagrams.
- Add a new diagram when the README behavior contract needs it, such as Consul
  Session/KV acquire-release and TTL recovery semantics.
- After every module, promote repeated visual failures into `bluetape4k-diagram`
  instead of relying on memory.

## Outcome

`leader-ktor` now uses a component architecture view, `leader-spring-boot`
replaces stale architecture and reentrant sequence visuals, and `leader-consul`
adds a source-backed acquire/release sequence next to its architecture diagram.
`leader-dynamodb` adds a second sequence diagram because the key reader problem
is not component ownership alone: DynamoDB TTL cleanup is eventual, while
correctness is decided by `leaseExpiry` and owner-guarded conditional writes.

## DynamoDB Follow-up

For backend modules where correctness and cleanup use different fields or
primitives, pair the component architecture with a lifecycle or sequence view.
Architecture can show the caller-owned table and lock-client dependency, but a
sequence diagram is better for conditional acquire, auto-extend, release,
min-lease retention, and contention skip behavior.

During visual review, treat explanatory note boxes as collision candidates, not
just arrows. A note can still obscure a lifeline or clip long field lists even
when the arrows look clean. Move notes into empty participant corridors and
split long field lists before rendering the final PNG.

## Hazelcast Follow-up

Diagram refresh can expose stale adjacent README prose. The Hazelcast README
still claimed that no watchdog renewal exists, but source inspection showed
single-leader electors start auto-extension when `autoExtend` is enabled. Fix
the prose and section title in the same module pass instead of preserving a
misleading README around a correct diagram.

For class diagrams, do not force every source concept into a connected edge. A
factory/extension entrypoint can be clearer as a separate entrypoint card when
create arrows would cross elector-to-lock dependency lines. Keep inheritance
vertical where possible, route dependency lines through empty corridors, and
shrink or split long class labels before accepting the PNG.

## Verification

- SVG XML parsing passed for changed assets.
- PNGs were rendered with CairoSVG and inspected individually.
- Forbidden legacy-layout/work-metadata scans passed.
- README image links were verified for changed module README files.
- `git diff --check` passed.

## Future Guidance

For each module, finish the module commit first, then add/update a lesson and
promote repeated guidance into the diagram skill before starting the next
module. Visual review must include frame separation, footer readability,
canvas/viewBox background coverage, and whether the diagram answers a real
README/source-backed reader question.
