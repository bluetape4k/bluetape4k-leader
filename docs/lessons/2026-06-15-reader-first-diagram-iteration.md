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

## Verification

- SVG XML parsing passed for changed assets.
- PNGs were rendered with CairoSVG and inspected individually.
- Forbidden Graphviz/work-metadata scans passed.
- README image links were verified for changed module README files.
- `git diff --check` passed.

## Future Guidance

For each module, finish the module commit first, then add/update a lesson and
promote repeated guidance into the diagram skill before starting the next
module. Visual review must include frame separation, footer readability,
canvas/viewBox background coverage, and whether the diagram answers a real
README/source-backed reader question.
