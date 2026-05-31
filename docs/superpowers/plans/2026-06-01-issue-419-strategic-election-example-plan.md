# Issue #419 Strategic Election Example Plan

## Step 0: Worktree

- Branch: `feat/issue-419-strategic-election-example`
- Worktree: `.worktrees/feat-issue-419-strategic-election-example`
- Base: `origin/develop`

## Step 1: Requirements

- Use the Type A full-feature lane because this adds a new example module plus
  CI/workflow wiring.
- Reuse the existing strategic election API from `leader-core`.
- Keep the example backend-neutral to avoid Docker/Testcontainers scope.

## Step 2: Implementation Tasks

1. Register `examples:strategic-election` in `settings.gradle.kts`.
2. Add `examples/strategic-election/build.gradle.kts` with the `application`
   plugin and a dependency on `bluetape4k-leader-core`.
3. Implement the strategic election demo.
   - Domain profiles with health/capacity/success-rate inputs.
   - Custom scorer plus `WeightedScorer` and `ScoredElectionStrategy`.
   - Report model with selected and skipped outcomes.
4. Add deterministic tests for winner selection and skip behavior.
5. Add `README.md` and `README.ko.md` for the example.
6. Update root README locale set.
7. Wire CI and Examples workflow coverage.
8. Add a concise lessons entry.

## Step 3: Validation Plan

Run in order:

1. `./gradlew projects`
2. `./gradlew :examples:strategic-election:test`
3. `./gradlew :examples:strategic-election:run`
4. `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`

## Step 4: Review Gates

- Spec review: confirm no new public API or backend dependency is introduced.
- Plan review: confirm all new-module registration points are covered.
- Code review: check deterministic scoring, skip semantics, serializable report
  models, README/source drift, and workflow YAML expression correctness.

## Step 5: Stop Conditions

- Stop as complete only after targeted verification, local review, lesson,
  commit, push, and PR creation.
- Stop as blocked if existing `leader-core` strategic API cannot express the
  required scoring scenario without public API changes.
