# Issue 491 Example Scenario and Flow Diagram Spec

- Issue: #491 `docs(examples): add scenario and flow diagrams`
- Parent: #486
- Work type: Type E Maintenance with `bluetape4k-diagram`
- Baseline: `develop` at `06e76493`

## Context

The example README set mostly has Architecture and Sequence diagrams. The
ZooKeeper scheduler already has Scenario and Flow diagrams, but
`examples/dynamodb-export` has no README diagrams, and the remaining examples do
not have Flow diagrams even when the source exposes explicit acquired, skipped,
conflict, retry, release, or next-cycle outcomes.

## Source Authority

Current source files and tests own diagram steps and outcome names:

- `examples/batch-scheduler/.../BatchScheduler.kt`: `run` returns job result or
  `null` on contention.
- `examples/cache-warmer/.../CachePartitionWarmer.kt`: each partition maps to
  warmed, skipped, or failed, and failure does not stop later partitions.
- `examples/dynamodb-export/.../DynamoDbScheduledExportRunner.kt`: elected node
  writes one export record; contenders return `SKIPPED`.
- `examples/migration-gate/.../MigrationGate.kt`: precheck, lock, in-lock
  recheck, migration, post-skip, skipped, and failed outcomes are first-class.
- `examples/webhook-poller/.../WebhookPoller.kt`: leader cycle claims events,
  handles success, requeues retryable failures, and terminally marks `FAILED`.
- `examples/rate-limiter/.../LeaderDispatchScheduler.kt`: scheduler returns
  `SCHEDULED` for one leader and `REJECTED` for contenders; workers consume
  Bucket4j quota before calling the external API.
- `examples/tenant-aggregator/.../TenantAggregator.kt`: one loop per tenant,
  per-tenant lock, skip on non-leader, aggregate exception isolation, and
  graceful stop.
- `examples/k8s-lease/.../K8sLeaseLeaderElectionExample.kt`: create, conflict,
  release, renew/reacquire, and holder identity checks.
- `examples/redisson-watchdog/.../RedissonWatchdogJobRunner.kt`: acquired,
  skipped, auto-extend, body execution, and release.
- `examples/consul-maintenance/.../ServiceMaintenanceCoordinator.kt`,
  `examples/etcd-reconciler/.../ControlPlaneReconciler.kt`,
  `examples/k8s-operator/.../OperatorController.kt`,
  `examples/ktor-app/.../KtorAppMain.kt`,
  `examples/strategic-election/.../StrategicElectionDemo.kt`,
  `examples/virtual-thread-runner/.../VirtualThreadLeaderRunner.kt`, and
  `examples/prometheus-dashboard/.../PrometheusDashboardApp.kt` provide simpler
  acquired/skipped or selected/skipped flows that should still get a compact Flow
  diagram for README scanability.

## Scope

1. Add a full diagram set for `examples/dynamodb-export`:
   Architecture, Flow, and Sequence diagrams.
2. Add Flow diagrams to every example README that currently has Architecture and
   Sequence diagrams but no Flow diagram.
3. Keep the existing ZooKeeper Scenario, Architecture, Flow, and Sequence
   diagrams, but include them in validation so the batch remains coherent.
4. Update `README.md` and `README.ko.md` together for every changed example.
5. Keep generated diagram labels English. Keep surrounding README prose localized.

## Non-Scope

- No production Kotlin behavior changes.
- No new examples or Gradle module wiring.
- No new localized diagram variants.
- No broad redraw of existing Architecture or Sequence diagrams unless the new
  batch reveals a mandatory gate failure.

## Diagram Requirements

- Flow diagrams use visible layer bands when the flow crosses trigger, election,
  work, storage, and result boundaries.
- Connector colors are semantic, not decorative:
  - neutral setup: muted gray
  - leader/success path: green
  - skipped/failure path: pink/red
  - contention/release path: amber
  - retry/reacquire/next-cycle path: purple
- Multi-segment routes use 90-degree bends and boundary approaches.
- Components inside layer bands are vertically centered enough to avoid bottom
  bias.
- When a layout becomes cramped, enlarge the canvas before compromising
  margins, line routing, or label spacing.
- Every README diagram has `.svg` and `.png`.
- Every new node-and-connector diagram has `.dot`, `.plain`,
  `-graphviz.svg`, and `-graphviz.png` evidence.
- Generators print deterministic geometry summaries before PNG rendering.

## Acceptance Criteria

- `examples/dynamodb-export/README.md` and `README.ko.md` embed PNG diagrams for
  Architecture, Flow, and Sequence.
- Every other changed example README embeds a new Flow diagram PNG.
- README files embed PNG only and never SVG.
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check` passes.
- `xmllint --noout docs/images/readme-diagrams/*.svg` passes.
- Changed PNGs are inspected at readable README scale, with a contact sheet for
  the batch and individual inspection for suspect diagrams.
- `git diff --check` passes.

## Risks

- Large batches can hide visual defects. Mitigation: contact sheet plus
  individual inspection of dense or changed diagrams.
- Flow diagrams can become repetitive. Mitigation: each flow must use labels and
  route colors that map to source-specific outcomes.
- Generator-only validation can miss visible defects. Mitigation: inspect
  rendered PNGs after generation and fix before PR.
