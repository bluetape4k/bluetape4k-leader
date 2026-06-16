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

## Micrometer Follow-up

The Micrometer README also carried stale summary prose: it said there were two
instrumentation paths while source and meter constants showed AOP recorder,
direct decorators, listener events, and history sink counters. For observability
modules, diagram the path from source event to meter family to registry/export,
then make the README overview and meter catalog match that same set of paths.

For observability examples such as `examples/prometheus-dashboard`, separate
the leader execution path from the metrics scrape/query path. Prometheus and
Grafana observe meters; they do not trigger leader work. Long Spring bean names
such as `LeaderScheduledTrigger` and `LeaderScheduledJob proxy` should be split
into compact reader-facing titles before rendering instead of being squeezed
inside cards.

## Batch Scheduler Follow-up

Example modules need diagrams that preserve the example's behavioral contract,
not just a generic architecture shape. For `examples/batch-scheduler`, the
source-backed reader questions were: which replica runs, what losers receive,
where TTL recovery appears, and whether `nodeId` participates in lock ownership.

Keep source evidence in SVG metadata or commit evidence, not in the rendered
footer. Use the footer for the module path or another reader-facing locator.
For flow diagrams, draw leader-only release paths around contention cards
instead of through them; if a route label only repeats an obvious arrow, remove
it. For sequence diagrams, keep call-label colors in the same family as the
call line so numbered calls remain visually tied to their arrows.

## Cache Warmer Follow-up

Do not draw every concurrent attempt when the source behavior is an `N x M`
contention matrix. The cache-warmer scenario became readable only after grouping
the three warmer instances and fanning out to one lock per partition; drawing
all node-to-partition attempts made the core contract look like a routing knot.

For example flows, avoid drawing every non-terminal branch back to the loop
when the cards can state `then continue`. Multiple return lines from success,
skip, and failure branches crossed the cancellation and result cards. A single
loop/back edge plus branch-local text explains the same source contract with
less visual noise.

When converting curved routes to orthogonal routes, do not let multiple branch
lines share the same vertical or horizontal lane. Parallel branches need their
own corridor and enough standoff from card edges; otherwise the rendered PNG
still reads as an overlap even if the SVG path technically stays outside the
card interior.

## Rate Limiter Follow-up

Rate-limiter examples combine two independent controls, so diagrams should not
collapse them into one generic "Redis" path. Show leader election as the
single-dispatch control and Bucket4j as the shared external-call quota control.
Readers need to see that dispatch losers prevent duplicate work, while quota
rejections prevent external API calls.

For flow and sequence diagrams, keep the dispatch decision and the quota
decision in separate vertical bands or corridors. A consumed-token branch can
easily cut through the quota card if it is drawn directly to the external API;
route it around the quota component and give the consumed/rejected labels enough
vertical separation inside the alt/loop region.

## Redisson Watchdog Follow-up

Watchdog diagrams should distinguish the short initial lease from the
source-backed auto-extension contract. A component diagram can show
`autoExtend = true` on the options card, but lease renewal and contender skip
behavior are clearer in scenario or sequence diagrams where the elected body
outlives the initial lease.

Avoid long decorative renewal loops in architecture diagrams. They tend to
cross group boundaries or look like a runtime flow. Put the auto-extension fact
inside the option card and reserve explicit renewal arrows for the sequence
timeline.

## Strategic Election Follow-up

Strategic election is not a backend contention example. The key reader
question is why the selected node is allowed to run: profiles become
`CandidateInfo`, scorers produce a score map, the strategy ranks candidates,
and local electors enforce selected-vs-skipped reports.

For suitability diagrams, do not draw skip routes through the selected work
card. Non-winner skip paths should use separate outer corridors so the selected
maintenance path remains visually and semantically independent.

## Remaining Examples Follow-up

Example sequence diagrams cannot be safely generated from a fixed actor order.
The initial remaining-example pass rendered clean lines but gave several
modules misleading message directions, such as a node calling another node
instead of calling the elector. Model the sequence as leader attempt, selected
work, and contender skip, then map those calls to the actual participants before
accepting the image.

Architecture summary cards should carry module-specific reader contracts, not a
generic "leader-only" sentence. If the same sentence could appear under
TenantAggregator, WebhookPoller, and Ktor App, it is probably process metadata
rather than user-facing documentation.

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
