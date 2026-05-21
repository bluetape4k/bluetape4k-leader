# Issue 248/229 Examples Plan

## Step 0: Worktree

- Branch: `feat/issue-248-229-examples`
- Worktree: `.worktrees/feat-issue-248-229-examples`
- Base: `develop`

## Step 1: Requirements

- Implement #248 first, then #229.
- Use `$bluetape4k-workflow` Type A Full Design because this work adds new
  example modules and workflow coverage.
- Keep #231 out of scope unless #248 exposes a small reusable helper that is
  clearly useful for a future `leader-k8s` module.

## Step 2: Implementation Tasks

1. Add missing dependency aliases to `gradle/libs.versions.toml`.
   - fabric8 Kubernetes client.
   - Bucket4j core and Lettuce artifacts.
   - `bluetape4k-bucket4j`.
2. Register modules in `settings.gradle.kts`.
   - `examples:k8s-lease`
   - `examples:rate-limiter`
3. Implement #248.
   - Add `examples/k8s-lease/build.gradle.kts`.
   - Add `K8sLeaseLeaderElectionExample`.
   - Add `K8sLeaseLeaderElectionExampleTest` tagged `@Tag("k8s")`.
   - Add English and Korean README files.
4. Implement #229.
   - Add `examples/rate-limiter/build.gradle.kts`.
   - Add demo classes and test.
   - Add English and Korean README files.
5. Wire workflows.
   - CI path filters and rate-limiter test job.
   - Scheduled/manual examples workflow entries for both modules.
   - K3s example runs only through `k8sTest`.
6. Add lessons entry.

## Step 3: Validation Plan

Run in order:

1. `./gradlew projects`
2. `./gradlew :examples:k8s-lease:compileKotlin :examples:k8s-lease:compileTestKotlin`
3. `./gradlew :examples:rate-limiter:test`
4. `./gradlew :examples:k8s-lease:k8sTest` only if privileged Docker is available.
5. `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`

If K3s cannot run locally, compile the module and report the K3s runtime gap.

## Step 4: Review Gates

- Spec review: verify scope excludes `leader-k8s` backend and #231.
- Plan review: verify new-module CI/examples workflow checklist is complete.
- Code review: focus on resource cleanup, coroutine cancellation, Testcontainers
  singleton use, workflow wiring, and README/API drift.

## Step 5: Stop Conditions

- Stop as complete only after targeted verification and lessons are done.
- Stop as blocked if `bluetape4k-testcontainers` published artifact lacks
  `K3sServer` and no local project substitution is configured.
- Stop as blocked if Bucket4j dependency resolution fails and no compatible
  artifact alias can be added without a broader dependency upgrade.
