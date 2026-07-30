# Leader Election Visual Companions Design

**Date:** 2026-07-30

**Repository:** `bluetape4k/bluetape4k-leader`

**Issues:** `#633`, `#634`, `#635`; publication follow-up `bluetape4k/bluetape4k.github.io#305`

**Release baseline:** `0.4.0` at `17ab7f872c1f96318c73d3580729cac20a67e017`

## Context

The Leader manual explains single- and group-leader contracts, Redis Lettuce behavior, and Spring Boot annotations as separate pages. Readers still have to reconstruct the time-dependent relationship among lock acquisition, token ownership, lease TTL, action duration, contention, expiry, extension, and release.

This change adds two bilingual standalone visual companions owned by the source repository:

1. A detailed `LeaderElector` companion that establishes the lock-and-lease model.
2. A compact `LeaderGroupElector` companion that reuses the first model and explains only the `1 -> N` slot delta.

The companions are educational simulations. They do not connect to Redis, execute library code, or claim to reproduce backend scheduling precision.

## Goals

- Explain the blocking `LeaderElector` contract through a guided, interactive Redis Lettuce timeline.
- Make normal contention visibly resolve to skip (`null` or `LeaderRunResult.Skipped`) rather than an exception.
- Distinguish `waitTime`, `leaseTime`, `minLeaseTime`, action duration, expiry, release, takeover, and `autoExtend`.
- Map the same execution boundary to Spring Boot `@LeaderElection`.
- Explain `LeaderGroupElector` as the same lease model with up to `maxLeaders` independently owned slots.
- Show `activeCount`, `availableSlots`, `isFull`, saturation, and admission after a slot becomes available.
- State that group election limits concurrency but does not partition or assign work.
- Map group behavior to `@LeaderGroupElection` and state its stream constraints.
- Provide English and Korean source-equivalent pages with automatic, light, and dark themes.
- Publish through the established source-manifest and GitHub Pages snapshot flow.

## Non-goals

- Simulating async, coroutine, virtual-thread, Reactor, or Kotlin Flow scheduling.
- Comparing Redis Lettuce with Redisson or any other backend.
- Demonstrating fencing tokens or promising exactly-once business effects.
- Teaching Redis deployment, connection pooling, failure detection, or cluster topology.
- Reproducing wall-clock precision, Redis command latency, or watchdog jitter.
- Turning `LeaderGroupElector` into a work queue, partition allocator, or shard coordinator.
- Embedding a runtime dependency, network request, analytics script, or build framework.

## Source Ownership and Publication

The source repository owns the canonical artifacts:

```text
docs/visual-companions/manifest.json
docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html
docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.ko.html
docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html
docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html
docs/manual/assets/visual-companions/
scripts/validate-visual-companions.mjs
tests/visual-companions/validator.test.mjs
```

The source PR targets `develop` from `docs/leader-election-visual-companions`. After that PR is merged, `bluetape4k.github.io` snapshots the exact merge commit in a separate PR targeting `develop` from `docs/publish-leader-visual-companions`.

The manual links to the published companion route as the primary interactive experience and includes a repository-owned 2x PNG fallback for non-interactive browsing and visual review. The standalone HTML remains the canonical visualization.

## Shared Experience Model

Both companions use the same five-step guided flow:

1. **Model** — identify candidates, Redis ownership data, and the protected action.
2. **Settings** — inspect the small set of parameters that changes the scenario.
3. **Direct API** — connect the timeline to `runIfLeader` and `runIfLeaderResult`.
4. **Spring mapping** — show the equivalent annotation boundary and configuration.
5. **Failure and recovery** — observe contention, expiry, single-leader extension, and later admission.

The layout keeps the timeline and current state visible while the learner moves between steps. It uses semantic buttons, labels, status text, and a live region so meaning is not color-only.

### Common Controls

Controls are intentionally bounded:

- Action duration.
- `leaseTime`.
- Reset and play/pause.
- Theme: auto, light, or dark.

The `LeaderElector` scenario presets are contention, expiry/takeover, and extension. The `LeaderGroupElector` presets are available capacity, saturation/skip, and expiry/later admission. The group page does not present an extension preset because group options do not provide `autoExtend` and explicit active-lock extension is outside the simulation.

The controls use discrete values rather than unconstrained input. Each change resets the simulation to a deterministic initial state.

### Shared Timeline Semantics

The timeline advances in fixed logical ticks. It displays educational ordering, not production timing:

```text
request -> wait/acquire -> action -> release
                         -> lease expiry -> later acquire
single only              -> periodic extension -> release
```

Every acquisition receives an opaque token. Release or extension succeeds only when the presented token still owns the lock or slot. The visualization must not imply that a process which has outlived its lease can safely release or extend a successor's ownership.

## Companion 1: LeaderElector

### Teaching Contract

The first companion is the detailed foundation. One logical `lockName` admits at most one compliant owner at a time.

The default scene contains three candidates (`node-a`, `node-b`, and `node-c`), one Redis lock record, and one protected action lane. The selected preset controls how their attempts overlap.

### Redis Lettuce Representation

The visualization presents the backend as a conceptual record:

```text
lockName -> { token, owner, ttl }
```

This is a teaching representation, not a promise about Redis key serialization. It must show:

- atomic acquisition when the lock is absent;
- an opaque ownership token;
- TTL countdown from `leaseTime`;
- token-checked extension;
- token-checked release;
- lock disappearance after valid release or lease expiry.

### Configuration Semantics

| Setting | Visual meaning |
| --- | --- |
| `waitTime` | Maximum logical window in which a contender may retry before it skips. |
| `leaseTime` | TTL granted at acquisition and restored by each successful extension. |
| `minLeaseTime` | Minimum successful hold duration before an early-finishing action releases. It must not exceed `leaseTime`. |
| action duration | How long the protected action remains active in the simulation. |
| `autoExtend` | Starts periodic lease extension while the action is active on the single-leader path. |

`waitTime` is explained in the guided copy but remains preset-controlled rather than exposed as a free slider. This keeps the control surface small while preserving the contention model.

### Scenario Presets

#### Contention

- `node-a` acquires the lock and runs the action.
- `node-b` attempts during ownership.
- If ownership remains active beyond `node-b`'s wait window, `node-b` skips.
- The result panel maps the skip to `null` and `LeaderRunResult.Skipped`.
- No normal contention exception is shown.

#### Expiry and Takeover

- `node-a` acquires a lease shorter than its action duration.
- `autoExtend` is disabled.
- The lease expires while `node-a` is still running.
- A later `node-b` attempt acquires a new token and becomes the compliant owner.
- `node-a` is shown as a stale worker and cannot release `node-b`'s token.
- The copy warns that leader election alone does not fence external side effects and that actions must be idempotent.

#### Extension

- `node-a` acquires a lease shorter than its action duration.
- `autoExtend` is enabled.
- Periodic successful extensions restore the TTL before expiry.
- Contenders skip while the extended ownership remains valid.
- The action completes and token-checked release removes the lock.

### Direct API Mapping

The companion shows source-equivalent Kotlin snippets:

```kotlin
val value = elector.runIfLeader("invoice-close") {
    closeInvoices()
}
```

and:

```kotlin
when (val result = elector.runIfLeaderResult("invoice-close") { closeInvoices() }) {
    is LeaderRunResult.Elected -> showCompleted(result.value)
    LeaderRunResult.Skipped -> showSkipped()
    is LeaderRunResult.ActionFailed -> showFailure(result.cause)
}
```

The page explicitly distinguishes an action returning `null` from `LeaderRunResult.Skipped`; `runIfLeaderResult` removes that ambiguity.

### Spring Boot Mapping

The Spring panel uses the same lock identity and timings:

```kotlin
@LeaderElection(
    name = "invoice-close",
    waitTime = "PT0.5S",
    leaseTime = "PT10S",
    minLeaseTime = "PT1S",
    autoExtend = true,
)
fun closeInvoices(): CloseSummary? = service.closeInvoices()
```

It explains that:

- AspectJ compile-time weaving guards the invocation;
- `@EnableAspectJAutoProxy` is not required;
- Kotlin methods do not need to be `open`;
- private methods are not intercepted;
- `name` may be a valid SpEL expression;
- synchronous and suspend values plus `Mono`, `Flux`, and `Flow` are supported;
- long streams require `autoExtend = true`, or `streamBounded = true` only when completion is guaranteed inside the lease.

The simulation stays synchronous. Other execution models are compatibility notes, not alternate animation engines.

### State Model

The renderer derives all visible output from one plain data state:

```text
scenario
logicalTick
playing
actionDuration
waitTime
leaseTime
minLeaseTime
autoExtend
candidates[]
lock { token, owner, acquiredAt, expiresAt }?
events[]
resultByCandidate
```

The state transition function is deterministic for a given control selection.

## Companion 2: LeaderGroupElector

### Teaching Contract

The second companion begins with the statement:

```text
LeaderElector: 1 lock -> at most 1 leader
LeaderGroupElector: 1 group -> at most N occupied slots
```

It does not repeat the detailed lock/lease lesson. Each occupied slot still has an opaque token and lease; the delta is the admission capacity.

### Additional Controls

- Candidate count.
- `maxLeaders`.

Candidate count must allow both available-capacity and saturated states. `maxLeaders` uses a bounded positive range and the page prevents invalid values below one.

The direct `LeaderGroupElectionOptions` contract accepts `maxLeaders >= 1`, while Spring startup validation requires `@LeaderGroupElection.maxLeaders > 1` and directs single-leader use cases to `@LeaderElection`. The Spring mapping must make that stricter boundary explicit.

### Scenario Presets

- **Available capacity** — candidates acquire independent tokens while fewer than `maxLeaders` slots are occupied.
- **Saturation and skip** — the group becomes full and a contender that reaches its wait deadline skips without an exception.
- **Expiry and later admission** — an occupied slot expires and a later candidate acquires newly available capacity with a new token.

Explicit group lease extension remains a compatibility note and is not animated.

### Group State

The page continuously derives and displays:

```text
activeCount
availableSlots = maxLeaders - activeCount
isFull = activeCount >= maxLeaders
```

Candidates that arrive while a slot is available acquire independent slot tokens and run concurrently. A candidate that cannot acquire within its wait window skips. Once a token is released or expires, a later candidate may occupy the newly available capacity.

### Work Distribution Boundary

The main warning is persistent:

> Slot ownership limits concurrency. It does not assign unique work.

The visual must not show slot indexes as business partitions. If candidates need exclusive work allocation, the application must provide a queue, partition map, claim table, or another work-distribution mechanism.

### Direct API Mapping

```kotlin
val group = connection.leaderGroupElection(
    LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = 500.milliseconds,
        leaseTime = 10.seconds,
    )
)

val value = group.runIfLeader("thumbnail-workers") {
    processNextClaimedBatch()
}

val state = group.state("thumbnail-workers")
```

The state card maps directly to `LeaderGroupState` and its derived properties.

### Spring Boot Mapping

```kotlin
@LeaderGroupElection(
    name = "thumbnail-workers",
    maxLeaders = 3,
    waitTime = "PT0.5S",
    leaseTime = "PT10S",
)
fun processNextClaimedBatch(): BatchSummary? = service.processNextClaimedBatch()
```

The delta panel states:

- synchronous, suspend, and `Mono` return types are supported;
- `Flux` and Kotlin `Flow` are rejected because per-slot stream lease extension is undefined;
- the group options do not provide `autoExtend`;
- explicit extension can be performed through the active lock extension contract when required, but it is not simulated here.

### Group State Model

The group renderer extends the shared state with:

```text
candidateCount
maxLeaders
slots[] { token, owner, acquiredAt, expiresAt }
activeCount
availableSlots
isFull
```

No slot index is exposed as a stable business identity.

## Locale Parity

English and Korean pages share:

- DOM structure;
- CSS and JavaScript behavior;
- control ranges and scenario data;
- API snippets and technical identifiers;
- accessible names and status semantics;
- theme behavior.

Only explanatory prose and UI labels differ. Korean text is authored as native technical prose, not literal translation. A validator checks source-equivalent structural markers, companion IDs, control IDs, scenario IDs, release metadata, and required technical anchors.

## Visual and Interaction Design

- Standalone HTML with inline CSS and JavaScript only.
- No external fonts, images, scripts, stylesheets, fetches, or module imports.
- Responsive two-column desktop layout that collapses to one column.
- Dark diagram styling with restrained blue, violet, cyan, amber, green, and red semantic accents.
- Theme support through `prefers-color-scheme` plus explicit auto/light/dark controls.
- Minimum 44px interactive targets.
- Visible focus indicators and keyboard-operable controls.
- Motion disabled or reduced under `prefers-reduced-motion`.
- Status, ownership, and result labels accompany all colors.
- Cards and timeline labels must remain readable at full-size desktop and narrow mobile captures.

## Manifest Contract

`docs/visual-companions/manifest.json` contains two entries with:

- stable companion ID;
- source repository and release metadata;
- issue references;
- English and Korean source paths;
- intended public routes;
- title and summary by locale;
- manual entry points;
- fallback image paths;
- required validation anchors.

The manifest is deterministic and contains no generated timestamps.

## Validation

### Structural Validator

`scripts/validate-visual-companions.mjs` verifies:

- the manifest schema and unique IDs;
- release ref and commit consistency;
- every locale source and fallback path exists;
- no network-capable markup or external asset reference;
- required locale, theme, guided-step, scenario, control, API, Spring, and accessibility anchors;
- single companion coverage of contention, expiry/takeover, extension, token, TTL, skip, and `LeaderRunResult`;
- group companion coverage of `maxLeaders`, state fields, saturation, and the work-distribution warning;
- source-equivalent English/Korean structure.

Node tests include passing repository fixtures and bounded negative fixtures for missing locale files, external assets, missing anchors, duplicate IDs, and parity drift.

### Manual Validation

The repository manual sequence remains the release-documentation gate:

```bash
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
```

### Browser and Visual Validation

Each English/Korean page is tested at desktop and mobile sizes for:

- load without console or page errors;
- all guided steps;
- all scenario presets;
- play, pause, reset, and control changes;
- theme selection;
- keyboard focus;
- meaningful final state and result labels.

Deterministic visual evidence uses a fixed viewport, fixed control state, reduced motion, and local file loading. Each locale/theme capture is produced twice; dimensions and hashes must match. The reviewed default-state 2x PNG fallbacks are committed under `docs/manual/assets/visual-companions/`.

Full-size inspection confirms:

- all card text fits;
- no clipping or overlap;
- connectors and directional cues remain visible;
- dark and light contrast is readable;
- mobile ordering preserves the teaching sequence.

## Manual Integration

The following bilingual pages receive concise “Visual companion” links without duplicating the companion content:

```text
docs/manual/en/core/single-group-strategic.md
docs/manual/ko/core/single-group-strategic.md
docs/manual/en/frameworks/spring-boot.md
docs/manual/ko/frameworks/spring-boot.md
```

The core page links both companions and positions the group page as the delta from single election. The Spring page links to the annotation-mapping steps in both companions. Links use the final public routes while release source links remain pinned to the manual release commit.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| The simulation is mistaken for Redis protocol documentation. | Label the Redis record as conceptual and keep backend claims tied to the 0.4.0 Lettuce implementation. |
| Lease expiry is mistaken for business exactly-once safety. | Show stale action overlap and a persistent idempotency/fencing warning. |
| Group slots are mistaken for work partitions. | Avoid stable numeric business identities and keep the work-distribution warning visible. |
| English and Korean behavior drifts. | Share state markers and validate structure, controls, scenarios, and anchors. |
| Theme or responsive layout regresses. | Capture deterministic desktop/mobile matrices and inspect full-size images. |
| The site snapshot diverges from source. | Publish only from the exact merged source commit and record it in the site snapshot metadata. |

## Delivery Boundary

The source PR is complete when the manifest, both bilingual companion pairs, fallbacks, validator tests, manual links, manual validation, browser checks, deterministic captures, and final review pass on the exact head.

The source PR is not merged without a fresh exact-head approval. Site snapshot publication begins only after that merge and is delivered through its own PR and merge approval gate.
