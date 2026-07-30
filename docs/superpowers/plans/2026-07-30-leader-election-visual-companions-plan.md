# Leader Election Visual Companions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish bilingual, deterministic visual companions that teach the Redis Lettuce `LeaderElector` lock-and-lease model and the `LeaderGroupElector` 1-to-N slot delta, then register exact-commit snapshots in `bluetape4k.github.io`.

**Architecture:** `bluetape4k-leader` owns the manifest, four standalone HTML documents, validator, tests, manual links, and reviewed PNG fallbacks. The single-leader companion defines the complete time-dependent model; the group companion reuses that model and adds only bounded slot admission and state. `bluetape4k.github.io` snapshots the exact merged source commit in a separate PR, so site work cannot begin until the source merge gate has passed.

**Tech Stack:** Static HTML/CSS/JavaScript, Node.js built-in test runner, Playwright browser automation, Ruby manual validators, Gradle, Astro/Starlight.

---

## File Map

### `bluetape4k-leader`

- Create `docs/visual-companions/manifest.json`
  - Registers two public companion IDs, four locale files, release metadata, public routes, manual entry points, and fallback images.
- Create `scripts/validate-visual-companions.mjs`
  - Validates manifest shape, path containment, standalone HTML, release/design baselines, locale parity, required technical anchors, interaction controls, accessibility, and fallback images.
- Create `tests/visual-companions/validator.test.mjs`
  - Exercises the passing repository and negative fixtures for duplicate IDs, missing files, external surfaces, missing anchors, and locale drift.
- Create `docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html`
  - English detailed single-leader companion.
- Create `docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.ko.html`
  - Korean source-equivalent single-leader companion.
- Create `docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html`
  - English group delta companion.
- Create `docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html`
  - Korean source-equivalent group delta companion.
- Create four 2x reviewed PNG fallbacks under `docs/manual/assets/visual-companions/`
  - `leader-elector.en.png`
  - `leader-elector.ko.png`
  - `leader-group-elector.en.png`
  - `leader-group-elector.ko.png`
- Modify `docs/manual/en/core/single-group-strategic.md`
- Modify `docs/manual/ko/core/single-group-strategic.md`
- Modify `docs/manual/en/frameworks/spring-boot.md`
- Modify `docs/manual/ko/frameworks/spring-boot.md`

### `bluetape4k.github.io` after the source merge

- Modify `src/data/visual-companions/repositories.json`
- Modify `src/data/visual-companions/catalog.json`
- Modify `tests/visual-companions/repositories.test.mjs`
- Modify `tests/visual-companions/navigation.test.mjs`
- Create `src/data/visual-companions/bluetape4k-leader.snapshot.json`
- Create four snapshot routes under:
  - `public/visual-companions/bluetape4k-leader/`
  - `public/ko/visual-companions/bluetape4k-leader/`

## Task 0: Prove the isolated baseline

**Files:**
- Verify only; do not modify repository files.

- [ ] **Step 1: Confirm the worktree baseline**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git rev-parse origin/develop
```

Expected: the branch is clean, contains only the committed design and plan documents, and its parent baseline is the current `origin/develop`.

- [ ] **Step 2: Run the manual baseline sequence**

```bash
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
```

Expected: every baseline command exits zero. If a command fails, diagnose the baseline before visual-companion mutation and do not attribute it to this change.

## Task 1: Prove the source publication contract

**Files:**
- Create: `tests/visual-companions/validator.test.mjs`
- Create: `docs/visual-companions/manifest.json`
- Create: `scripts/validate-visual-companions.mjs`

- [ ] **Step 1: Write the failing process test**

Create the test harness:

```javascript
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import test from 'node:test';

const execute = promisify(execFile);
const root = new URL('../../', import.meta.url);

async function validate() {
  return execute('node', ['scripts/validate-visual-companions.mjs'], { cwd: root });
}

test('approved leader visual companions satisfy the repository contract', async () => {
  const { stdout } = await validate();
  assert.match(stdout, /2 documents \\/ 4 locale files \\/ 4 fallbacks/);
});
```

- [ ] **Step 2: Run the test and verify the missing validator failure**

Run:

```bash
node --test tests/visual-companions/validator.test.mjs
```

Expected: FAIL because `scripts/validate-visual-companions.mjs` does not exist.

- [ ] **Step 3: Add the manifest**

Create this contract:

```json
{
  "schemaVersion": 1,
  "repository": "bluetape4k/bluetape4k-leader",
  "release": {
    "ref": "0.4.0",
    "commit": "17ab7f872c1f96318c73d3580729cac20a67e017"
  },
  "documents": [
    {
      "id": "leader-elector",
      "source": "docs/superpowers/specs/2026-07-30-leader-election-visual-companions-design.md",
      "status": "approved",
      "public": true,
      "presentation": {
        "mode": "simulation",
        "defaultView": "simulation",
        "views": ["simulation"]
      },
      "manuals": [
        "docs/manual/en/core/single-group-strategic.md",
        "docs/manual/ko/core/single-group-strategic.md",
        "docs/manual/en/frameworks/spring-boot.md",
        "docs/manual/ko/frameworks/spring-boot.md"
      ],
      "locales": {
        "en": {
          "title": "LeaderElector Lock and Lease",
          "html": "docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html",
          "route": "/visual-companions/bluetape4k-leader/leader-elector/",
          "fallback": "docs/manual/assets/visual-companions/leader-elector.en.png"
        },
        "ko": {
          "title": "LeaderElector 락과 리스",
          "html": "docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.ko.html",
          "route": "/ko/visual-companions/bluetape4k-leader/leader-elector/",
          "fallback": "docs/manual/assets/visual-companions/leader-elector.ko.png"
        }
      }
    },
    {
      "id": "leader-group-elector",
      "source": "docs/superpowers/specs/2026-07-30-leader-election-visual-companions-design.md",
      "status": "approved",
      "public": true,
      "presentation": {
        "mode": "simulation",
        "defaultView": "simulation",
        "views": ["simulation"]
      },
      "manuals": [
        "docs/manual/en/core/single-group-strategic.md",
        "docs/manual/ko/core/single-group-strategic.md",
        "docs/manual/en/frameworks/spring-boot.md",
        "docs/manual/ko/frameworks/spring-boot.md"
      ],
      "locales": {
        "en": {
          "title": "LeaderGroupElector Slot Capacity",
          "html": "docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html",
          "route": "/visual-companions/bluetape4k-leader/leader-group-elector/",
          "fallback": "docs/manual/assets/visual-companions/leader-group-elector.en.png"
        },
        "ko": {
          "title": "LeaderGroupElector 슬롯 수용량",
          "html": "docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html",
          "route": "/ko/visual-companions/bluetape4k-leader/leader-group-elector/",
          "fallback": "docs/manual/assets/visual-companions/leader-group-elector.ko.png"
        }
      }
    }
  ]
}
```

- [ ] **Step 4: Implement the validator core**

Export `validateRepository(inputRoot = process.cwd())`. Resolve `realpath(inputRoot)`, reject paths outside the root, require schema version 1, the exact repository name, release `0.4.0`, release commit `17ab7f872c1f96318c73d3580729cac20a67e017`, two unique kebab-case IDs, English/Korean entries, exact route prefixes, and existing source/manual/HTML/fallback files.

Reject these surfaces:

```javascript
const forbidden = [
  /<script\b[^>]*\bsrc\s*=/i,
  /<link\b[^>]*\brel\s*=\s*["']?stylesheet\b/i,
  /<(?:img|iframe|audio|video|source)\b[^>]*\bsrc\s*=\s*["'](?!data:|#)[^"']+["']/i,
  /<form\b/i,
  /\bfetch\s*\(/,
  /\bXMLHttpRequest\b/,
  /\bWebSocket\s*\(/,
  /\bnavigator\.sendBeacon\s*\(/,
  /\bEventSource\s*\(/,
];
```

For every HTML file require:

```javascript
const commonPatterns = [
  /^\s*<!doctype html>/i,
  /<meta\b[^>]*name=["']color-scheme["'][^>]*content=["']light dark["']/i,
  /:root\[data-theme=["']light["']\]/i,
  /:root\[data-theme=["']dark["']\]/i,
  /prefers-reduced-motion/i,
  /data-design-baseline=["']ed346a14bf223b0eb456198fdee617b608795b54["']/i,
  /data-release-ref=["']0\.4\.0["']/i,
  /data-release-commit=["']17ab7f872c1f96318c73d3580729cac20a67e017["']/i,
  /data-step=["']model["']/i,
  /data-step=["']settings["']/i,
  /data-step=["']direct-api["']/i,
  /data-step=["']spring["']/i,
  /data-step=["']recovery["']/i,
  /aria-live=["']polite["']/i,
  /aria-pressed=/i,
];
```

Require companion-specific markers:

```javascript
const contracts = {
  'leader-elector': [
    'LeaderElector',
    'runIfLeader',
    'runIfLeaderResult',
    'LeaderRunResult.Elected',
    'LeaderRunResult.Skipped',
    'LeaderRunResult.ActionFailed',
    'waitTime',
    'leaseTime',
    'minLeaseTime',
    'autoExtend',
    'data-scenario="contention"',
    'data-scenario="expiry"',
    'data-scenario="extension"',
    '@LeaderElection',
  ],
  'leader-group-elector': [
    'LeaderGroupElector',
    'LeaderGroupState',
    'maxLeaders',
    'activeCount',
    'availableSlots',
    'isFull',
    'data-control="candidate-count"',
    'data-control="max-leaders"',
    'data-scenario="capacity"',
    'data-scenario="saturation"',
    'data-scenario="expiry"',
    '@LeaderGroupElection',
    'Flux',
    'Flow',
  ],
};
```

- [ ] **Step 5: Add negative fixture tests**

Copy a minimal valid repository into `mkdtemp(path.join(tmpdir(), 'leader-visual-'))`, mutate one contract at a time, and assert rejection messages for:

```text
duplicated document ID
missing Korean HTML
path escapes repository
external script or stylesheet
missing release baseline
missing scenario or state marker
English/Korean section ID drift
missing fallback PNG
```

Use `t.after(() => rm(fixtureRoot, { recursive: true, force: true }))` only on the explicit `mkdtemp` result.

- [ ] **Step 6: Run the tests and verify artifact absence is the only repository failure**

Run:

```bash
node --test tests/visual-companions/validator.test.mjs
```

Expected: the negative fixtures pass; the repository process test reports the four missing HTML files and four missing fallback images.

- [ ] **Step 7: Commit the contract**

Commit with Lore trailers. Record that the red state is intentional and limited to artifacts implemented by the next tasks.

## Task 2: Build the English LeaderElector companion

**Files:**
- Modify: `tests/visual-companions/validator.test.mjs`
- Create: `docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html`

- [ ] **Step 1: Add failing single-leader behavior assertions**

Read the English file and assert exact section IDs, step IDs, scenario IDs, control IDs, three candidate IDs, Redis lock fields, timeline events, direct API snippets, Spring annotation fields, and the stale-owner warning.

Use the structural sets:

```javascript
const electorSections = [
  'model',
  'settings',
  'direct-api',
  'spring',
  'recovery',
  'sources',
];
const electorScenarios = ['contention', 'expiry', 'extension'];
const electorControls = ['action-time', 'lease-time'];
```

- [ ] **Step 2: Run the targeted test**

Run:

```bash
node --test --test-name-pattern="LeaderElector" tests/visual-companions/validator.test.mjs
```

Expected: FAIL because the English single-leader HTML is missing.

- [ ] **Step 3: Implement the standalone shell**

Create semantic header, five-step navigation, settings and scenario controls, simulation panel, state cards, code panels, caveats, and release sources. Inline all CSS and JavaScript. Resolve `auto`, `light`, and `dark` before the first style paint, persist explicit theme choice, and disable animation under reduced motion.

- [ ] **Step 4: Implement the deterministic transition model**

Use fixed logical ticks and this state shape:

```javascript
const state = {
  scenario: 'contention',
  logicalTick: 0,
  playing: false,
  actionTime: 8,
  waitTime: 2,
  leaseTime: 10,
  minLeaseTime: 1,
  autoExtend: false,
  candidates: [
    { id: 'node-a', attemptAt: 0 },
    { id: 'node-b', attemptAt: 1 },
    { id: 'node-c', attemptAt: 6 },
  ],
  lock: null,
  events: [],
  results: {},
};
```

Define pure `resetScenario(name)`, `advanceOneTick()`, `tryAcquire(candidate)`, `extendLease(owner)`, `releaseIfOwner(owner)`, and `render()` functions. Tokens are deterministic labels such as `token-a-1`; no random number or wall-clock API participates in rendered state.

- [ ] **Step 5: Encode the three scenario contracts**

- Contention: `node-a` remains owner through `node-b`'s wait deadline; `node-b` becomes skipped.
- Expiry: `node-a` action outlives its lease; `node-b` later acquires a new token; stale `node-a` release is rejected.
- Extension: `node-a` renews before expiry while the action remains active; contenders skip; the final owner releases successfully.

Each event row exposes tick, candidate, operation, token, TTL, and outcome as text.

- [ ] **Step 6: Add direct API and Spring mapping**

Include complete `runIfLeader`, `runIfLeaderResult`, and `@LeaderElection` snippets from the design. Explain AspectJ CTW, no `@EnableAspectJAutoProxy`, non-open Kotlin methods, private-method rejection, valid SpEL, synchronous simulation scope, and async/reactive compatibility notes.

- [ ] **Step 7: Add exact release sources**

Link the pinned release paths for:

```text
LeaderElector.kt
LeaderElectionOptions.kt
LeaderRunResult.kt
LettuceLeaderElector.kt
LeaderElection.kt
docs/manual/en/architecture/runtime-model.md
```

- [ ] **Step 8: Run the targeted tests**

Run:

```bash
node --test --test-name-pattern="LeaderElector" tests/visual-companions/validator.test.mjs
```

Expected: PASS for English single-leader content; other repository artifacts remain absent.

- [ ] **Step 9: Commit the English companion**

Commit with Lore trailers and record that Korean parity and browser rendering remain unverified.

## Task 3: Add the Korean LeaderElector companion

**Files:**
- Modify: `tests/visual-companions/validator.test.mjs`
- Create: `docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.ko.html`

- [ ] **Step 1: Add failing locale parity assertions**

Compare English and Korean sets for section IDs, guided-step values, scenario values, control values, candidate IDs, event data fields, code-block count, source-link paths, release metadata, and ARIA live regions.

- [ ] **Step 2: Implement source-equivalent Korean HTML**

Keep CSS, JavaScript transition logic, state values, technical identifiers, code, commands, and link targets equivalent. Translate explanatory prose and UI labels into natural Korean. Use `락`, `리스`, `소유권 token`, `경쟁`, `건너뜀`, and `만료 후 재획득` consistently.

- [ ] **Step 3: Run the parity tests**

Run:

```bash
node --test --test-name-pattern="LeaderElector|locale" tests/visual-companions/validator.test.mjs
```

Expected: all LeaderElector and its locale-parity assertions pass.

- [ ] **Step 4: Commit the Korean companion**

Commit with Lore trailers. Record that the locale structure and state machine are equivalent.

## Task 4: Build the English LeaderGroupElector delta companion

**Files:**
- Modify: `tests/visual-companions/validator.test.mjs`
- Create: `docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html`

- [ ] **Step 1: Add failing group behavior assertions**

Require:

```javascript
const groupSections = [
  'model',
  'settings',
  'direct-api',
  'spring',
  'recovery',
  'sources',
];
const groupControls = ['candidate-count', 'max-leaders', 'action-time', 'lease-time'];
const groupStateFields = ['activeCount', 'availableSlots', 'isFull'];
const groupScenarios = ['capacity', 'saturation', 'expiry'];
```

Assert that the persistent warning says slot capacity does not assign unique work and that no business partition is derived from a slot index.

- [ ] **Step 2: Run the targeted test**

Run:

```bash
node --test --test-name-pattern="LeaderGroupElector" tests/visual-companions/validator.test.mjs
```

Expected: FAIL because the English group HTML is missing.

- [ ] **Step 3: Implement the group delta shell**

Open with the `1 -> N` comparison and link the detailed `LeaderElector` sibling. Reuse the five guided steps but keep lock/lease prose compact.

- [ ] **Step 4: Implement deterministic slot admission**

Use this state extension:

```javascript
const state = {
  scenario: 'capacity',
  logicalTick: 0,
  playing: false,
  candidateCount: 5,
  maxLeaders: 2,
  actionTime: 8,
  waitTime: 2,
  leaseTime: 10,
  minLeaseTime: 1,
  candidates: [],
  slots: [],
  events: [],
  results: {},
};
```

Derive:

```javascript
const activeCount = state.slots.length;
const availableSlots = state.maxLeaders - activeCount;
const isFull = activeCount >= state.maxLeaders;
```

Admit candidates while capacity exists, skip a candidate after its wait deadline when full, and admit a later candidate after release or expiry. Give each occupied slot an opaque token without presenting a stable business partition number.

- [ ] **Step 5: Encode the three group scenario contracts**

- Available capacity: candidates acquire independent tokens while `activeCount < maxLeaders`.
- Saturation: the group becomes full and a contender skips after its wait deadline.
- Expiry: one slot expires and a later candidate receives a new token in the newly available capacity.

Do not animate group lease extension.

- [ ] **Step 6: Add API and Spring delta mapping**

Include `LeaderGroupElectionOptions`, `runIfLeader`, `state`, and `@LeaderGroupElection` examples. State:

- direct options accept `maxLeaders >= 1`;
- Spring annotation validation requires `maxLeaders > 1`;
- synchronous, suspend, and `Mono` return types are supported;
- `Flux` and `Flow` are rejected;
- group options have no `autoExtend`;
- explicit active-lock extension is possible but not animated.

- [ ] **Step 7: Add exact release sources**

Link:

```text
LeaderGroupElector.kt
LeaderGroupElectionOptions.kt
LeaderGroupState.kt
LettuceLeaderGroupElector.kt
LettuceSlotTokenGroup.kt
LeaderGroupElection.kt
docs/manual/en/core/single-group-strategic.md
```

- [ ] **Step 8: Run the group tests**

Run:

```bash
node --test --test-name-pattern="LeaderGroupElector" tests/visual-companions/validator.test.mjs
```

Expected: PASS for English group content.

- [ ] **Step 9: Commit the English group companion**

Commit with Lore trailers. Record that Korean parity and browser rendering remain unverified.

## Task 5: Add the Korean LeaderGroupElector companion

**Files:**
- Modify: `tests/visual-companions/validator.test.mjs`
- Create: `docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html`

- [ ] **Step 1: Add failing group locale-parity assertions**

Compare section, step, control, scenario, state-field, candidate, event-field, code-block, source-link, release metadata, and ARIA sets.

- [ ] **Step 2: Implement source-equivalent Korean HTML**

Keep the state machine and structure equivalent. Use native Korean prose for slot capacity, saturation, later admission, and the work-distribution boundary. Preserve API, annotation, property, and return-type identifiers.

- [ ] **Step 3: Run group and parity tests**

Run:

```bash
node --test --test-name-pattern="LeaderGroupElector|locale" tests/visual-companions/validator.test.mjs
```

Expected: all group and parity assertions pass.

- [ ] **Step 4: Commit the Korean group companion**

Commit with Lore trailers. Record that the group companion remains a delta rather than a duplicate single-leader tutorial.

## Task 6: Add manual entry points and deterministic fallbacks

**Files:**
- Modify: `docs/manual/en/core/single-group-strategic.md`
- Modify: `docs/manual/ko/core/single-group-strategic.md`
- Modify: `docs/manual/en/frameworks/spring-boot.md`
- Modify: `docs/manual/ko/frameworks/spring-boot.md`
- Create: `docs/manual/assets/visual-companions/leader-elector.en.png`
- Create: `docs/manual/assets/visual-companions/leader-elector.ko.png`
- Create: `docs/manual/assets/visual-companions/leader-group-elector.en.png`
- Create: `docs/manual/assets/visual-companions/leader-group-elector.ko.png`

- [ ] **Step 1: Add failing manual and fallback assertions**

Require the four public routes in the corresponding manual locales, require the four fallback paths from the manifest, and validate PNG signature plus 2x capture dimensions.

- [ ] **Step 2: Add concise manual links**

Add one “Visual companions” section to each core page and one annotation-mapping callout to each Spring page. The English links use `/visual-companions/...`; Korean links use `/ko/visual-companions/...`. Do not duplicate the simulation explanation in the manual.

- [ ] **Step 3: Run baseline browser interactions**

For all four local HTML files, use fixed desktop `1440x1000` and mobile `390x844` viewports with reduced motion. Verify:

```text
no console errors
no page errors
five guided steps reachable
every scenario selectable
play, pause, reset
every bounded control value updates state
auto, light, dark theme
keyboard focus remains visible
live status text changes
```

- [ ] **Step 4: Capture deterministic PNG fallbacks**

Capture the reviewed dark-theme default scene at 2x device scale for each locale file. Produce each capture twice from reset state and compare dimensions and SHA-256 hashes. Copy only the matching reviewed output into the four manifest fallback paths.

- [ ] **Step 5: Inspect full-size images**

Open every committed PNG at original size and verify text fit, card boundaries, connectors, directional cues, contrast, and locale-specific wrapping.

- [ ] **Step 6: Run validator tests**

Run:

```bash
node --test tests/visual-companions/validator.test.mjs
node scripts/validate-visual-companions.mjs
```

Expected: PASS with `2 documents / 4 locale files / 4 fallbacks`.

- [ ] **Step 7: Commit manual integration and fallbacks**

Commit with Lore trailers and include deterministic hash comparison plus full-size inspection in `Tested:`.

## Task 7: Run repository validation and final review

**Files:**
- Review all source-branch changes.

- [ ] **Step 1: Run the complete manual validation sequence**

```bash
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
```

Expected: every command exits zero.

- [ ] **Step 2: Run static repository checks**

```bash
git diff origin/develop...HEAD --check
node --test tests/visual-companions/validator.test.mjs
node scripts/validate-visual-companions.mjs
./gradlew detekt
```

Expected: every command exits zero.

- [ ] **Step 3: Run the available review command**

The documented `codex run review --retry 3` surface is unavailable in the installed CLI. Use:

```bash
codex review --base origin/develop
```

Fix every HIGH or CRITICAL finding and rerun the affected validations. Record lower-severity findings and their disposition.

- [ ] **Step 4: Verify exact branch state**

Confirm the worktree is clean, the branch contains only intended commits, every changed path is in the approved scope, and `origin/develop...HEAD` contains no runtime or dependency change.

## Task 8: Deliver the source PR and stop at the merge gate

**Files:**
- No new repository files.

- [ ] **Step 1: Push the exact source branch**

Push `docs/leader-election-visual-companions` and record the exact head SHA.

- [ ] **Step 2: Create the English PR**

Create a PR to `develop` that links `#633`, `#634`, and `#635`, describes the two-companion teaching model, lists all validation evidence, and ends with:

```markdown
## DoD Status

- [x] Source manifest and four locale documents
- [x] Validator and negative tests
- [x] Manual links and deterministic fallbacks
- [x] Manual validation
- [x] Browser and full-size visual review
- [x] Exact-head final review
```

- [ ] **Step 3: Re-read live PR state**

Verify live title/body, base/head, exact head SHA, checks, reviews, comments, review threads, and mergeability. Correct reversible PR metadata drift.

- [ ] **Step 4: Report merge readiness**

Report the exact PR and head with current CI and review evidence. Stop before merge and request fresh approval.

## Task 9: Publish the site snapshot after the source merge

**Files:**
- Modify: `bluetape4k.github.io/src/data/visual-companions/repositories.json`
- Modify: `bluetape4k.github.io/src/data/visual-companions/catalog.json`
- Modify: `bluetape4k.github.io/tests/visual-companions/repositories.test.mjs`
- Modify: `bluetape4k.github.io/tests/visual-companions/navigation.test.mjs`
- Create generated snapshot metadata and four public route files.

- [ ] **Step 1: Pin the merged source commit**

Fetch the source repository and derive the immutable source reference from the merged PR:

```bash
source_merge_sha=$(gh pr view --repo bluetape4k/bluetape4k-leader --json mergeCommit --jq '.mergeCommit.oid')
git -C ../bluetape4k-leader merge-base --is-ancestor "$source_merge_sha" origin/develop
```

Write `source_merge_sha` as `sourceRef` for repository `bluetape4k/bluetape4k-leader` with manifest path `docs/visual-companions/manifest.json`. Reject an empty value or a commit that is not an ancestor of `origin/develop`.

- [ ] **Step 2: Add catalog metadata and failing registry/navigation tests**

Register `leader-elector` and `leader-group-elector` with bilingual summaries. Require repository navigation and all four final routes.

- [ ] **Step 3: Generate the immutable snapshot**

Run the existing site snapshot command against the local source checkout at the pinned commit. Verify generated HTML hashes match source locale files and snapshot metadata records the same commit.

- [ ] **Step 4: Run site checks**

Run:

```bash
npm test
npm run check:manual
npm run build
```

Expected: all tests and manual snapshot checks pass; Astro emits no errors.

- [ ] **Step 5: Verify preview routes**

Open all four routes in English and Korean, check navigation, theme, interaction, console/page errors, and source metadata.

- [ ] **Step 6: Commit, push, and create the site PR**

Use branch `docs/publish-leader-visual-companions`, base `develop`, link `bluetape4k.github.io#305`, and end the English body with `## DoD Status`.

- [ ] **Step 7: Report site merge readiness**

Re-read exact head, live body, checks, reviews, threads, and mergeability. Stop before merge and request a second fresh approval.
