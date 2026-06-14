# bluetape4k-leader Reader-First Diagram Plan

## Context

This plan replaces the failed batch-first approach. The goal is not to polish old SVGs.
The goal is to decide what each README reader needs to understand, then redraw each
diagram from current README and source evidence.

Existing SVG/PNG files are historical hints only. They are not source truth.

## Global Skill Gates

Every diagram must pass these gates before rendering:

- Source model: current README section plus source/config/benchmark evidence is listed before drawing.
- Reader question: the diagram answers one concrete reader question.
- Purpose title: title starts with a human-readable purpose phrase, not a lowercase slug or module id.
- Visual form: choose architecture, class, sequence, flow, ERD, or chart because it matches the reader question.
- No stale redraw: old diagram labels/routes cannot be reused unless current source evidence still proves them.
- No relationship-heavy grid: grid layout is allowed only for charts or true inventory/matrix subjects.
- No hidden routes: connectors must not pass under cards, labels, layer gutters, footers, or titles.
- Boundary endpoints: routes visibly land on source/target boundaries in the rendered PNG.
- Semantic routes: dense or branching routes use meaningful colors for success, skip, contention, release, retry, dependency, or metric paths.
- Fonts: final SVG text uses only explicit `Architects Daughter` and `Comic Mono`.
- Markers: non-sequence connector markers are compact; sequence follows the approved projects baseline.
- Evidence: node-and-route diagrams keep `.dot`, `.plain`, `-graphviz.svg`, and `-graphviz.png` evidence.
- README embeds: README files embed PNG, not SVG.
- Visual review: inspect rendered PNG for every changed diagram, not only contact sheets.

## Work Order

1. Plan every asset by reader question and source evidence.
2. Audit the plan against the skill Do NOT list.
3. Establish shared scripts by diagram kind before drawing assets.
4. Redraw one group at a time.
5. Validate generated SVG/PNG/evidence for that group.
6. Inspect rendered PNGs for that group.
7. Update this plan status before moving to the next group.

Do not run another whole-repo regeneration pass as the primary workflow.

## Script Architecture

The `bluetape4k-projects` repo is the reference shape: diagram work is split by
kind, with common rendering and validation helpers underneath. The previous
leader-local batch scripts are rejected because they mixed kinds, patched old
assets after the fact, and made it too easy to bypass skill gates.

External helper skills are subordinate to this plan and the bluetape4k diagram
skill. `fireworks-tech-graph` may inform generic SVG+PNG helper checks such as
edge anchoring, legend placement, and arrow-label clearance. The
`architecture-diagram-generator` dark-theme/JetBrains/grid output is not a
final README style for bluetape4k assets; it is useful only as an architecture
composition reference when converted back into the approved pastel README
language. Class diagrams stay on the class-specific Graphviz/UML pipeline.

Current leader script layout:

- `scripts/readme-diagrams/lib/svg-core.mjs`: shared palette, explicit fonts,
  compact markers, SVG/PNG writing, Graphviz evidence writing, and forbidden
  token checks.
- `scripts/readme-diagrams/lib/node-diagram-renderer.mjs`: architecture, flow,
  ERD, and module-style node/route diagrams with boundary endpoint validation.
- `scripts/readme-diagrams/lib/sequence-renderer.mjs`: projects-style sequence
  renderer with rectangular participant headers, dashed lifelines, numbered
  message pills, colored arrowheads, dashed returns, and branch labels placed in
  participant gaps.
- `scripts/readme-diagrams/lib/class-diagram-renderer.mjs`: class-specific UML
  compartment renderer with source-backed stereotypes, parent-above-child
  validation, semantic relationship colors, label lane placement, and Graphviz
  evidence generation.
- `scripts/readme-diagrams/root-core-models.mjs`: P0 root/core source-backed
  models. Future groups should add their own model files instead of stuffing
  every asset into one batch script.
- `scripts/readme-diagrams/module-sequence-models.mjs`: module README sequence
  models for core, Hazelcast, Kubernetes, Redis Lettuce, Redis Redisson, and
  Spring Boot reentrant AOP diagrams.
- `scripts/readme-diagrams/example-blueprint-models.mjs`: 17 example
  blueprints expanded into scenario, architecture, flow, and sequence models.
- `scripts/generate-root-readme-visuals.mjs`: root overview, runtime
  architecture, and README relationship map.
- `scripts/redraw-readme-sequence-diagrams.mjs`: sequence-only generation.
- `scripts/generate-module-sequence-diagrams.mjs`: module README
  sequence-only generation.
- `scripts/generate-graphviz-class-diagrams.mjs`: class-only entrypoint; starts
  with `leader-core-class-01` and must grow by class-group models only.
- `scripts/generate-readme-benchmark-charts.mjs`: chart-only entrypoint;
  root composition and benchmark chart models.
- `scripts/validate-readme-diagram-assets.mjs`: shared validator, with
  `DIAGRAM_VALIDATION_FILES` for one-group validation.

Rejected scripts removed from this plan:

- `scripts/regenerate-reviewed-readme-assets.mjs`
- `scripts/regenerate-reviewed-sequence-assets.mjs`
- `scripts/repair-reviewed-route-clearance.mjs`
- `scripts/validate-reviewed-readme-assets.mjs`

DoD for script architecture:

- Kind-specific entrypoints exist before more assets are drawn.
- Common renderer owns font, marker, conversion, and validation rules.
- P0 assets are generated through the kind-specific scripts, not through a
  one-off batch renderer.

## Priority Groups

| Priority | Group | Assets | Why first | Stop condition |
|---:|---|---:|---|---|
| P0 | Root and core understanding | 6 | First README impression and conceptual model. | Reader can explain what bluetape4k-leader is, how `runIfLeader` works, and how group slots differ. |
| P0 | Sequence diagrams | 30 | Current sequence assets were stale and did not match the approved projects baseline. | Each sequence shows participant headers, lifelines, numbered message pills, returns, and source-backed branches. |
| P0 | Class diagrams | 8 | Class diagrams have dense relationships and can mislead API understanding. | Parent/interface/component relationships are clear, colored, and not panoramic strips. |
| P1 | Backend architecture diagrams | 8 | Backend modules need runtime/client/storage understanding, not module inventory. | Each backend diagram shows runtime boundary, storage/client collaborator, lock/lease semantics, and failure/release path. |
| P1 | Example quadrants | 68 | Examples need a consistent four-image teaching pattern. | Each example scenario/architecture/flow/sequence answers a distinct reader question without duplication. |
| P2 | Charts and ERD | 7 | These are mostly data/table readers, but must still cite source tables. | Chart/table source is explicit and readable at README scale. |

Asset count: 6 + 30 + 8 + 8 + 68 + 7 = 127 because sequence/example/backend items overlap by purpose group. Unique final asset count remains 105.

## Root And Core Plan

| Asset | Reader question | Source evidence | Chosen form | Must show | Must not show |
|---|---|---|---|---|---|
| `root-readme-overview-01` | What problem does the repository solve and what families exist? | `README.md`, `settings.gradle.kts` | module overview architecture | Core API, backend families, Spring/Ktor/metrics/adapters, examples boundary | A generic module grid with no runtime meaning |
| `root-readme-module-chart-01` | How much of the repository is core/backend/example/support surface? | `settings.gradle.kts`, module READMEs | chart | Module family composition and relative scope | Architecture routes or fake dependency lines |
| `bluetape4k-leader-architecture-01` | How does a caller reach a backend lock/lease through the core API? | `README.md`, `leader-core`, backend modules | architecture | Caller, core elector contracts, backend adapters, lock/lease stores, result/metrics hooks | Lowercase slug title, backend inventory without runtime story |
| `bluetape4k-leader-sequence-02` | What exactly happens inside `runIfLeader`? | `README.md`, `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt` | sequence | acquire, skip, execute, release, result status | Old Mermaid-like sequence or unnumbered arrows |
| `bluetape4k-leader-sequence-03` | How do multiple leaders share bounded slots? | `README.md`, `SuspendLeaderGroupElector`, strategy code | sequence | slot namespace, token attempt, full/available branch, release | Treating group election as normal single lock |
| `readme-table-relationships-erd-01` | How do README tables relate to modules and source families? | `README.md`, module READMEs | ERD/table relationship | Table-to-module/documentation relationships | Table-to-class mappings not supported by source |

## Backend Module Plan

| Asset | Reader question | Source evidence | Chosen form | Must show | Must not show |
|---|---|---|---|---|---|
| `bluetape4k-leader-bom-architecture-01` | How does the BOM constrain downstream modules? | `bluetape4k-leader-bom/README.md`, `build.gradle.kts` | architecture | BOM, version constraints, consumer modules, dependency scope | Lines through module cards |
| `leader-consul-architecture-01` | How does Consul Session/KV ownership map to leader election? | `leader-consul/README.md`, `ConsulLeaderElector.kt` | architecture | caller, elector, Consul session, KV lock, release/failure semantics | Generic client-server boxes without session/KV distinction |
| `leader-dynamodb-architecture-01` | How does conditional write/TTL ownership provide leadership? | `leader-dynamodb/README.md`, DynamoDB elector source | architecture | conditional acquire, item/TTL, owner identity, release/expiry | A database box without conditional semantics |
| `leader-etcd-architecture-01` | How does etcd lease/key lifecycle drive leadership? | `leader-etcd/README.md`, etcd source | architecture | lease grant, key put, keepalive, revoke/expiry | Static module map |
| `leader-k8s-architecture-01` | How does Kubernetes Lease API implement lock ownership? | `leader-k8s/README.md`, Kubernetes lease source | architecture | Fabric8 client, Lease object, resourceVersion/renewTime, RBAC boundary | Hidden acquire/release sequence inside architecture boxes |
| `leader-micrometer-architecture-01` | Where are leader election metrics produced and consumed? | `leader-micrometer/README.md`, micrometer source | architecture | listener/aspect/elector metrics path, registry, Prometheus consumer | Metrics names without event source |
| `leader-spring-boot-architecture-01` | How does auto-configuration wire properties, AOP, electors, and metrics? | `leader-spring-boot/README.md`, auto-config and AOP source | architecture | properties, auto-config phases, aspect, elector beans, conditional collaborators | Long lines through layer gutters |
| `leader-ktor-sequence-01` | What does the Ktor management route call and return? | `leader-ktor/README.md`, `LeaderElectionManagementRoute.kt` | sequence | HTTP request, route handler, service/elector, DTO response | Calling this an architecture diagram |

## Class Diagram Plan

| Asset | Reader question | Source evidence | Chosen form | Must show | Must not show |
|---|---|---|---|---|---|
| `leader-core-class-01` | What public contracts and core result types should implementers know? | `leader-core/README.md`, core interfaces/classes | class | `SuspendLeaderElector`, group elector, options/result/events, local implementation | Factory clutter or disconnected one-row panorama |
| `leader-exposed-jdbc-class-01` | How do JDBC Exposed tables/repositories/electors relate? | `leader-exposed-jdbc/README.md`, JDBC source | class | table objects, repository/elector classes, lock/history relationship | Layer gutter routes and truncated title |
| `leader-exposed-r2dbc-class-01` | How do R2DBC Exposed support classes differ from JDBC? | `leader-exposed-r2dbc/README.md`, R2DBC source | class | coroutine repository support, tables, R2DBC elector classes | Showing JDBC-only types as primary |
| `leader-hazelcast-class-01` | Which Hazelcast primitives back single and group election? | `leader-hazelcast/README.md`, Hazelcast source | class | lock delegate, slot delegate, public electors, options | Same-color dense crossings |
| `leader-mongodb-class-01` | How do Mongo documents/options/electors compose? | `leader-mongodb/README.md`, Mongo source | class | document/collection classes, lock/group electors, factories/options | Routes through class cards |
| `leader-redis-lettuce-class-01` | Which Lettuce lock/slot classes implement Redis leadership? | `leader-redis-lettuce/README.md`, Lettuce source | class | lock delegate, slot token group, public electors, options | Routes through class cards |
| `leader-redis-redisson-class-01` | Which Redisson primitives back lock and semaphore election? | `leader-redis-redisson/README.md`, Redisson source | class | `RLock`, semaphore permit, electors, delegates | Generic Redis class names |
| `leader-zookeeper-class-01` | How do Curator recipes map to ZooKeeper leader classes? | `leader-zookeeper/README.md`, ZooKeeper source | class | elector, Curator lock, factories/coroutine support | Unexplained Curator internals as main nodes |

Class-specific gates:

- Use the class Graphviz pipeline: relationship table -> DOT -> plain -> sketch -> final SVG/PNG.
- Interfaces/classes above implementations.
- Relationship colors distinguish implementation, composition, dependency, storage/client usage.
- Do not show companion/factory helpers unless they answer the reader question.
- Generator prints the geometry summary before PNG rendering and fails on
  endpoint-angle, non-orthogonal bend, card interior crossing, route clearance,
  node/legend overlap, title gap, or outer-margin imbalance.
- Outer margin evidence must include concrete `margins=L/R/T/B` values. Current
  class diagrams target `96/96/96/96` after including the separated legend band
  in the content bounds.

## Sequence Plan

All sequence diagrams answer: what is the runtime call order, where can the path skip or fail, and what releases/retries?

Sequence baseline:

- Rectangular participant headers.
- Dashed lifelines without arrowheads.
- Numbered message pills placed clear of paths.
- Colored arrowheads for call/success/skip/contention/release/retry.
- Dashed return paths.
- Explicit alt/branch frame when a branch spans multiple messages.

| Asset group | Assets | Reader question pattern | Source evidence | Must show | Must not show |
|---|---|---|---|---|---|
| Core/root sequences | `bluetape4k-leader-sequence-02`, `bluetape4k-leader-sequence-03`, `leader-core-sequence-02`, `leader-core-sequence-03` | How does core acquire/skip/execute/release, and how do group slots differ? | root/core READMEs and core coroutine contracts | acquire, skip, body, result, release, slot/full branch | Same old sequence with only label/style changes |
| Backend sequences | `leader-hazelcast-sequence-02`, `leader-hazelcast-sequence-03`, `leader-k8s-sequence-02`, `leader-redis-lettuce-sequence-02`, `leader-redis-lettuce-sequence-03`, `leader-redis-redisson-sequence-02`, `leader-redis-redisson-sequence-03`, `leader-spring-boot-sequence-02`, `leader-ktor-sequence-01` | How does this backend/module turn core election into its runtime primitive? | module README plus delegate/aspect/route source | backend-specific primitive, contention, release/TTL/watchdog/reentrant behavior | Generic lock sequence that could belong to any backend |
| Example sequences | 17 `examples-*-sequence-01` assets | What happens when this example runs once or on the next cycle? | example README plus example `src/main` | trigger, election, leader path, skipped peer, terminal report/next cycle | Duplicating the flow diagram or hiding skip/retry branches |

## Example Diagram Plan

Each example keeps four distinct diagrams. They must not repeat the same content.

| Example | Scenario diagram asks | Architecture diagram asks | Flow diagram asks | Sequence diagram asks | Source evidence |
|---|---|---|---|---|---|
| `batch-scheduler` | What happens on a scheduled batch tick across nodes? | Which scheduler/elector/Redis/job components collaborate? | Which branch returns executed vs skipped? | What is the runtime message order for one tick? | `examples/batch-scheduler/README.md`, example source |
| `cache-warmer` | How are cache partitions warmed without duplicate work? | Which warmer, lock, cache, and backend pieces collaborate? | Which partition path executes or skips? | How does one partition lock cycle run? | `examples/cache-warmer/README.md`, example source |
| `consul-maintenance` | How does maintenance ownership prevent duplicate drain work? | How do coordinator, Consul Session, KV, and steps connect? | How does acquire/skip/release affect maintenance? | How do Session/KV calls order the run? | `examples/consul-maintenance/README.md`, example source |
| `dynamodb-export` | How is one export run protected by a logical lease? | How do runner, DynamoDB lock, export table, and peers collaborate? | What branch writes export rows vs skipped report? | How does conditional acquire order the run? | `examples/dynamodb-export/README.md`, example source |
| `etcd-reconciler` | How does a control loop avoid duplicate reconciliation? | Which reconciler, etcd lease, key, and resources collaborate? | What applies resources vs skips? | How does grant/put/revoke order the run? | `examples/etcd-reconciler/README.md`, example source |
| `k8s-lease` | How do holders compete for a Kubernetes Lease? | Which example, Fabric8 client, Lease, and holders collaborate? | What creates, conflicts, releases, and reacquires? | How do API calls order the acquire/release cycle? | `examples/k8s-lease/README.md`, example source |
| `k8s-operator` | How do operator pods coordinate reconciliation? | Which pods, controller, Lease, and workload collaborate? | What reconciles vs stands by? | How do ticks and Lease responses order the run? | `examples/k8s-operator/README.md`, example source |
| `ktor-app` | How does the app expose stats while background work is leader-only? | Which route, aggregator, plugin, and elector collaborate? | What path updates vs reads stats? | How does HTTP and scheduled work interact? | `examples/ktor-app/README.md`, example source |
| `migration-gate` | How does rolling deploy avoid duplicate migrations? | Which gate, lock, marker, and pods collaborate? | What branch applies vs observes marker? | How does marker check and lock acquisition order? | `examples/migration-gate/README.md`, example source |
| `prometheus-dashboard` | How do leader metrics become dashboard data? | Which job, metrics registry, Prometheus, and Grafana collaborate? | What path records and scrapes metrics? | How do job execution and scraping interleave? | `examples/prometheus-dashboard/README.md`, example source |
| `rate-limiter` | How does leader scheduling combine with quota consumption? | Which scheduler, lock, quota, worker, and API collaborate? | What path consumes quota or rejects? | How are schedule, quota, and API calls ordered? | `examples/rate-limiter/README.md`, example source |
| `redisson-watchdog` | How does a long job keep Redis leadership? | Which runner, Redisson lock, watchdog, and peers collaborate? | What path extends, completes, or skips? | How does watchdog extension order the run? | `examples/redisson-watchdog/README.md`, example source |
| `strategic-election` | How do scores decide the best candidate? | Which scorer, candidates, local elector, and winner collaborate? | What branch wins vs skips? | How are scoring and grant messages ordered? | `examples/strategic-election/README.md`, example source |
| `tenant-aggregator` | How does tenant-scoped leadership prevent duplicate aggregation? | Which aggregator, tenant lock, store, and peers collaborate? | What path executes per tenant vs skips? | How does tenant lock ordering work? | `examples/tenant-aggregator/README.md`, example source |
| `virtual-thread-runner` | How does leader-only work run on a virtual thread? | Which elector, local lock, virtual thread, and blocking task collaborate? | What path starts thread vs skips? | How does acquire/start/complete/release order? | `examples/virtual-thread-runner/README.md`, example source |
| `webhook-poller` | How does one node poll webhook batches? | Which poller, lock, queue, and standby node collaborate? | What path fetches/processes vs skips? | How does polling order one batch? | `examples/webhook-poller/README.md`, example source |
| `zookeeper-scheduler` | How does ZooKeeper locking protect a legacy scheduled job? | Which scheduler, Curator/ZooKeeper, and job collaborate? | What path executes, skips, and reacquires? | How do node-a/node-b/next-run messages order? | `examples/zookeeper-scheduler/README.md`, example source |

Example asset expansion:

- For every example above: `examples-<name>-scenario-01`
- For every example above: `examples-<name>-architecture-01`
- For every example above: `examples-<name>-flow-01`
- For every example above: `examples-<name>-sequence-01`

This expands to 17 x 4 = 68 assets.

## Chart Plan

| Asset | Reader question | Source evidence | Chosen form | Must show | Must not show |
|---|---|---|---|---|---|
| `leader-benchmark-distributed-throughput-chart-01` | Which distributed backend/setup has higher throughput? | benchmark README/tables/JMH source | chart | units, relative values, scenario labels, source caveat | Decorative bars without units |
| `leader-benchmark-distributed-latency-chart-01` | Which setup has lower latency and by how much? | benchmark README/tables/JMH source | chart | latency direction, values, unit labels | Throughput-style ranking without lower-is-better cue |
| `leader-benchmark-kubernetes-throughput-chart-01` | How does Kubernetes throughput compare across conditions? | benchmark README/tables/k8s result data | chart | k8s conditions, throughput units, source caveat | Generic distributed chart labels |
| `leader-benchmark-kubernetes-latency-chart-01` | How does Kubernetes latency compare across conditions? | benchmark README/tables/k8s result data | chart | latency units, lower-is-better cue, conditions | Missing axis/value labels |
| `leader-history-self-improve-throughput-chart-01` | Did self-improvement history change throughput? | benchmark README/history data | chart | before/after or trend values | Unsupported causal claims |
| `root-readme-module-chart-01` | What is the repository module composition? | `settings.gradle.kts`, module READMEs | chart | module family composition | Runtime architecture routes |

## Per-Group Execution Checklist

Before drawing each group:

- Read the README section and source files listed in the group plan.
- Confirm the old image is not being used as source truth.
- Write or update the group model data.
- Check applicable rejected patterns from the wiki catalog.

After drawing each group:

- Generate SVG and PNG.
- Generate Graphviz evidence when the diagram has nodes/routes.
- Run validator on the group.
- Open representative PNGs and inspect: title, source meaning, line/card overlap, endpoint visibility, label clearance, semantic colors, footer/title spacing.
- Update `Status` below.

## Status

| Group | Status | Evidence |
|---|---|---|
| Plan | PASS | Unique final PNG coverage checked: 105/105. Script architecture added after projects repo review. |
| Script architecture | PASS | Common lib and kind entrypoints added under `scripts/readme-diagrams/`; rejected batch scripts removed. |
| Root/core | PASS | Root overview, runtime architecture, README relationship map, module chart, two root sequences, two core module sequences, and core class diagram regenerated and validated. |
| Sequence | PASS | Root/core/backend/module/example sequences regenerated with projects-style renderer: participant headers, lifelines, numbered message pills, colored `5x5` markers, branch frames, and dashed returns. |
| Class | PASS | All 8 class assets regenerated with class-specific renderer, Graphviz evidence, validator pass, and direct PNG inspection: core, exposed JDBC, exposed R2DBC, Hazelcast, MongoDB, Redis Lettuce, Redis Redisson, ZooKeeper. |
| Backend architecture | PASS | All 8 backend module assets regenerated with kind-specific scripts, evidence files, validator pass, and direct PNG inspection: BOM, Consul, DynamoDB, etcd, Kubernetes, Micrometer, Spring Boot, and Ktor sequence. |
| Examples | PASS | All 68 example assets regenerated from `example-blueprint-models.mjs`; architecture/flow/scenario/sequence groups passed geometry gates and representative PNG inspection. |
| Charts/ERD | PASS | Six README/benchmark charts regenerated from `generate-readme-benchmark-charts.mjs`; ERD regenerated through node renderer; all include source/read intent metadata and final validation. |

Latest full verification:

- Command: `node scripts/generate-root-readme-visuals.mjs`
- Command: `node scripts/redraw-readme-sequence-diagrams.mjs`
- Command: `node scripts/generate-module-sequence-diagrams.mjs`
- Command: `node scripts/generate-backend-architecture-diagrams.mjs`
- Command: `node scripts/generate-backend-sequence-diagrams.mjs`
- Command: `node scripts/generate-graphviz-class-diagrams.mjs`
- Command: `node scripts/generate-example-architecture-diagrams.mjs`
- Command: `node scripts/generate-example-flow-diagrams.mjs`
- Command: `node scripts/generate-example-scenario-diagrams.mjs`
- Command: `node scripts/generate-example-sequence-diagrams.mjs`
- Command: `node scripts/generate-readme-benchmark-charts.mjs`
- Result: all generated groups printed `geometry=PASS` or `chart=PASS`
  before PNG rendering, including `margins=L/R/T/B:96/96/96/96`.
- Command: `node scripts/validate-readme-diagram-assets.mjs`
- Result: `leader diagram validation: total=105 failed=0`
- Command: `find docs/images/readme-diagrams docs/images/readme-charts -name '*.svg' -print0 | xargs -0 -n 1 xmllint --noout`
- Result: PASS
- Command: README SVG embed search for `docs/images/readme-(diagrams|charts)/*.svg`
- Result: no matches; READMEs embed PNG assets.
- Command: final SVG forbidden-token search for font and internal evidence text
- Result: no matches.
- Command: `git diff --check`
- Result: PASS
- PNG inspection: distributed throughput chart, distributed latency chart,
  `leader-spring-boot-sequence-02.png`, and
  `examples-zookeeper-scheduler-scenario-01.png` inspected after final repairs.

Latest P0 verification:

- Command: `node scripts/generate-root-readme-visuals.mjs`
- Command: `node scripts/redraw-readme-sequence-diagrams.mjs`
- Command: `DIAGRAM_VALIDATION_FILES=... node scripts/validate-readme-diagram-assets.mjs`
- Result: `leader diagram validation: total=6 failed=0`
- Command: `git diff --check -- scripts docs/superpowers/plans/... <P0 SVG files>`
- Result: PASS
- PNG inspection: `root-readme-overview-01.png`,
  `bluetape4k-leader-architecture-01.png`,
  `readme-table-relationships-erd-01.png`,
  `root-readme-module-chart-01.png`,
  `bluetape4k-leader-sequence-02.png`, and
  `bluetape4k-leader-sequence-03.png` were inspected after regeneration.

Latest class verification:

- Asset: `docs/images/readme-diagrams/leader-core-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-exposed-jdbc-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-exposed-r2dbc-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-hazelcast-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-mongodb-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-redis-lettuce-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-redis-redisson-class-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-zookeeper-class-01.svg/png`
- Evidence: `leader-core-class-01.dot`, `leader-core-class-01.plain`,
  `leader-core-class-01-graphviz.svg`, and
  `leader-core-class-01-graphviz.png`
- Evidence: `leader-exposed-jdbc-class-01.dot`,
  `leader-exposed-jdbc-class-01.plain`,
  `leader-exposed-jdbc-class-01-graphviz.svg`, and
  `leader-exposed-jdbc-class-01-graphviz.png`
- Evidence: `leader-exposed-r2dbc-class-01.dot`,
  `leader-exposed-r2dbc-class-01.plain`,
  `leader-exposed-r2dbc-class-01-graphviz.svg`, and
  `leader-exposed-r2dbc-class-01-graphviz.png`
- Evidence: `leader-hazelcast-class-01.dot`,
  `leader-hazelcast-class-01.plain`,
  `leader-hazelcast-class-01-graphviz.svg`, and
  `leader-hazelcast-class-01-graphviz.png`
- Evidence: `leader-mongodb-class-01.dot`,
  `leader-mongodb-class-01.plain`,
  `leader-mongodb-class-01-graphviz.svg`, and
  `leader-mongodb-class-01-graphviz.png`
- Evidence: `leader-redis-lettuce-class-01.dot`,
  `leader-redis-lettuce-class-01.plain`,
  `leader-redis-lettuce-class-01-graphviz.svg`, and
  `leader-redis-lettuce-class-01-graphviz.png`
- Evidence: `leader-redis-redisson-class-01.dot`,
  `leader-redis-redisson-class-01.plain`,
  `leader-redis-redisson-class-01-graphviz.svg`, and
  `leader-redis-redisson-class-01-graphviz.png`
- Evidence: `leader-zookeeper-class-01.dot`,
  `leader-zookeeper-class-01.plain`,
  `leader-zookeeper-class-01-graphviz.svg`, and
  `leader-zookeeper-class-01-graphviz.png`
- Command: `node scripts/generate-graphviz-class-diagrams.mjs`
- Result: every class asset printed `geometry=PASS` before PNG rendering with
  `badEndpointAngle=0`, `badBends=0`, `interiorCrossings=0`,
  `nodeOverlaps=0`, `laneClearance=0`, `marginImbalance=0`, and
  `margins=L/R/T/B:96/96/96/96`.
- Result: `leader-core-class-01: kind=class classes=14 relations=16 evidence=dot/plain/graphviz/final`; `leader-exposed-jdbc-class-01: kind=class classes=13 relations=13 evidence=dot/plain/graphviz/final`; `leader-exposed-r2dbc-class-01: kind=class classes=13 relations=14 evidence=dot/plain/graphviz/final`; `leader-hazelcast-class-01: kind=class classes=15 relations=20 evidence=dot/plain/graphviz/final`; `leader-mongodb-class-01: kind=class classes=17 relations=24 evidence=dot/plain/graphviz/final`; `leader-redis-lettuce-class-01: kind=class classes=17 relations=25 evidence=dot/plain/graphviz/final`; `leader-redis-redisson-class-01: kind=class classes=17 relations=25 evidence=dot/plain/graphviz/final`; `leader-zookeeper-class-01: kind=class classes=17 relations=26 evidence=dot/plain/graphviz/final`
- Command: `DIAGRAM_VALIDATION_FILES=docs/images/readme-diagrams/leader-core-class-01.svg,docs/images/readme-diagrams/leader-exposed-jdbc-class-01.svg,docs/images/readme-diagrams/leader-exposed-r2dbc-class-01.svg,docs/images/readme-diagrams/leader-hazelcast-class-01.svg,docs/images/readme-diagrams/leader-mongodb-class-01.svg,docs/images/readme-diagrams/leader-redis-lettuce-class-01.svg,docs/images/readme-diagrams/leader-redis-redisson-class-01.svg,docs/images/readme-diagrams/leader-zookeeper-class-01.svg node scripts/validate-readme-diagram-assets.mjs`
- Result: `leader diagram validation: total=8 failed=0`
- Marker repair: inheritance hollow-triangle markers now use the same
  `markerWidth="5" markerHeight="5" refX="4.5" refY="2.5"`
  marker box as `use`/dependency route arrows; generated class SVGs no longer
  contain old `markerWidth="14"`, `markerWidth="13"`, or `markerWidth="8"`
  markers.
- Evidence cleanup: generated class SVGs do not contain geometry or validation
  evidence text such as `margins=L/R/T/B`, `geometry=PASS`, `Graphviz`,
  `validation`, or `marginImbalance`.
- Command: `git diff --check`
- Result: PASS
- PNG inspection: all 8 class PNGs were re-opened after the legend-band and
  margin gate changes. Each has uniform outer margins, a separated legend band
  that does not cover class cards, compact `5x5` inheritance markers matching
  dependency/use markers, and boundary-attached routes with no card intrusion.

Latest backend architecture verification:

- Asset: `docs/images/readme-diagrams/bluetape4k-leader-bom-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-consul-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-dynamodb-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-etcd-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-k8s-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-micrometer-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg/png`
- Asset: `docs/images/readme-diagrams/leader-ktor-sequence-01.svg/png`
- Evidence: `bluetape4k-leader-bom-architecture-01.dot`,
  `bluetape4k-leader-bom-architecture-01.plain`,
  `bluetape4k-leader-bom-architecture-01-graphviz.svg`, and
  `bluetape4k-leader-bom-architecture-01-graphviz.png`
- Evidence: `leader-consul-architecture-01.dot`,
  `leader-consul-architecture-01.plain`,
  `leader-consul-architecture-01-graphviz.svg`, and
  `leader-consul-architecture-01-graphviz.png`
- Evidence: `leader-dynamodb-architecture-01.dot`,
  `leader-dynamodb-architecture-01.plain`,
  `leader-dynamodb-architecture-01-graphviz.svg`, and
  `leader-dynamodb-architecture-01-graphviz.png`
- Evidence: `leader-etcd-architecture-01.dot`,
  `leader-etcd-architecture-01.plain`,
  `leader-etcd-architecture-01-graphviz.svg`, and
  `leader-etcd-architecture-01-graphviz.png`
- Evidence: `leader-k8s-architecture-01.dot`,
  `leader-k8s-architecture-01.plain`,
  `leader-k8s-architecture-01-graphviz.svg`, and
  `leader-k8s-architecture-01-graphviz.png`
- Evidence: `leader-micrometer-architecture-01.dot`,
  `leader-micrometer-architecture-01.plain`,
  `leader-micrometer-architecture-01-graphviz.svg`, and
  `leader-micrometer-architecture-01-graphviz.png`
- Evidence: `leader-spring-boot-architecture-01.dot`,
  `leader-spring-boot-architecture-01.plain`,
  `leader-spring-boot-architecture-01-graphviz.svg`, and
  `leader-spring-boot-architecture-01-graphviz.png`
- Evidence: `leader-ktor-sequence-01.dot`,
  `leader-ktor-sequence-01.plain`,
  `leader-ktor-sequence-01-graphviz.svg`, and
  `leader-ktor-sequence-01-graphviz.png`
- Command: `node scripts/generate-backend-architecture-diagrams.mjs`
- Result: `bluetape4k-leader-bom-architecture-01: kind=architecture nodes=9 routes=8 evidence=dot/plain/graphviz/final`; `leader-consul-architecture-01: kind=architecture nodes=11 routes=13 evidence=dot/plain/graphviz/final`; `leader-dynamodb-architecture-01: kind=architecture nodes=12 routes=14 evidence=dot/plain/graphviz/final`; `leader-etcd-architecture-01: kind=architecture nodes=12 routes=14 evidence=dot/plain/graphviz/final`; `leader-k8s-architecture-01: kind=architecture nodes=12 routes=14 evidence=dot/plain/graphviz/final`; `leader-micrometer-architecture-01: kind=architecture nodes=13 routes=13 evidence=dot/plain/graphviz/final`; `leader-spring-boot-architecture-01: kind=architecture nodes=13 routes=13 evidence=dot/plain/graphviz/final`
- Command: `DIAGRAM_VALIDATION_FILES=docs/images/readme-diagrams/bluetape4k-leader-bom-architecture-01.svg,docs/images/readme-diagrams/leader-consul-architecture-01.svg,docs/images/readme-diagrams/leader-dynamodb-architecture-01.svg,docs/images/readme-diagrams/leader-etcd-architecture-01.svg,docs/images/readme-diagrams/leader-k8s-architecture-01.svg,docs/images/readme-diagrams/leader-micrometer-architecture-01.svg,docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg node scripts/validate-readme-diagram-assets.mjs`
- Result: `leader diagram validation: total=7 failed=0`
- Command: `node scripts/generate-backend-sequence-diagrams.mjs`
- Result: `leader-ktor-sequence-01: kind=sequence participants=6 events=17 baseline=projects-sequence`
- Command: `DIAGRAM_VALIDATION_FILES=docs/images/readme-diagrams/leader-ktor-sequence-01.svg node scripts/validate-readme-diagram-assets.mjs`
- Result: `leader diagram validation: total=1 failed=0`
- PNG inspection: `bluetape4k-leader-bom-architecture-01.png` inspected after
  removing a confusing reverse edge between the leader BOM and
  `bluetape4k-dependencies`. The diagram shows Gradle `java-platform`
  constraints, published dependencyManagement, Gradle/Maven imports,
  constraint-only artifact behavior, and managed module families without card
  overlap.
- PNG inspection: `leader-consul-architecture-01.png` inspected after reducing
  decorative routes and keeping only the runtime path from caller-owned
  endpoint/options through single/group electors, `ConsulLockClient`, Session
  TTL, single KV key, group slot KV keys, and Consul HTTP API without card
  overlap. Re-inspected after moving a Session route away from the lower band
  label.
- PNG inspection: `leader-dynamodb-architecture-01.png` inspected after
  rerouting and then removing duplicated outer acquire paths that created a
  dense edge web. The diagram shows caller-owned AWS clients/table, single and
  group electors, `DynamoDbLockClient` conditional writes, owner-token
  extension, single/group row namespaces, logical `leaseExpiry` correctness,
  and DynamoDB `ttl` cleanup metadata without card or layer-label overlap.
- PNG inspection: `leader-etcd-architecture-01.png` inspected after rerouting
  the `EtcdLockClient` to `Etcd Lease` path away from the lower band label and
  group key card. The diagram shows caller-owned jetcd client lifecycle,
  single/group electors, jetcd Lock acquire, lease handle keepalive,
  single/group Lock key namespaces, ownership-key watch events, and unlock or
  revoke cleanup without card or layer-label overlap.
- PNG inspection: `leader-k8s-architecture-01.png` inspected after moving the
  single-elector route below the middle band title. The diagram shows
  caller-owned Fabric8 client lifecycle, namespace/RBAC boundary, single/group
  Lease electors, `KubernetesLeaseLock`, owner-token fencing, renewTime
  extension, Lease object namespaces, resourceVersion conflict contention, and
  audit annotations without card or layer-label overlap.
- PNG inspection: `leader-micrometer-architecture-01.png` inspected after
  rerouting AOP/direct input lines and the adapter-to-registry fan-in away from
  band titles. The diagram shows Spring AOP callbacks, direct elector
  decorators, listener lifecycle counters, history sink counters,
  `MeterRegistry`, AOP/direct/event-history meter families, and Prometheus or
  backend naming export without card or layer-label overlap.
- PNG inspection: `leader-spring-boot-architecture-01.png` inspected after
  shortening phase-card titles and rerouting runtime fan-out paths away from
  band titles. The diagram shows AutoConfiguration import order,
  `LeaderProperties` binding, backend client gates, AOP factory registration,
  Micrometer recorder registration, CTW leader aspects, selectable factory
  beans, metrics/health beans, and the opt-in status endpoint without card or
  layer-label overlap.
- PNG inspection: `leader-ktor-sequence-01.png` inspected after replacing long
  Kotlin participant identifiers with reader-facing labels. The sequence shows
  plugin install validation, Application attribute storage, optional management
  route install, `leaderScheduled` lock registration, active-cycle
  `runIfLeader`, elected/action and exception-isolation branches, management
  `GET /management/leaderElection`, registry snapshot, per-lock
  `state(lockName)`, and direct JSON text response without participant or
  message overlap.
