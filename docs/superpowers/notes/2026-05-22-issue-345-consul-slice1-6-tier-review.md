# 6-Tier Code Review - Issue #345 Consul Slice 1

Scope: `leader-consul` contract skeleton, repository wiring, README entries, CI
and nightly workflow wiring.

Base constraint: this slice is stacked on PR #351 and uses
`bluetape4k-bom` 1.9.0 from the parent branch.

## Tier 1. Factual Correctness

- PASS: The module is registered in `settings.gradle.kts` and the BOM
  constraints.
- PASS: Public contract objects validate the Consul TTL floor and ceiling
  before runtime code can create invalid sessions.
- PASS: KV lock names reuse the shared `validateLockName` contract and encode
  the final lock-name segment for Consul HTTP paths.

Findings: none P0/P1.

## Tier 2. Senior Engineering / Maintainability

- PASS: Public API owns `ConsulEndpoint` and `ConsulLeaderElectionOptions`
  instead of exposing a third-party Consul client.
- PASS: The internal `ConsulLockClient` boundary keeps the future Java HTTP
  runtime implementation replaceable.
- PASS: Slice remains narrow: no group election, Spring, Ktor, or runtime
  elector contract is introduced before the single-leader runtime slice.

Findings: none P0/P1.

## Tier 3. Security

- FIXED: `ConsulEndpoint` now rejects URI user-info credentials. ACL material
  must use `aclToken`, and `toString()` masks that token.
- PASS: Query strings and fragments are rejected so tokens are not accidentally
  carried through endpoint URLs.
- PASS: No workflow or test output contains token literals beyond negative
  validation fixtures.

Findings after fix: none P0/P1.

## Tier 4. Consistency / Repository Fit

- PASS: CI and nightly jobs follow existing module job shape and are included
  in both aggregator `needs:` lists.
- PASS: Test resources mirror project defaults for JUnit lifecycle and logging.
- PASS: README and README.ko entries are paired.

Findings: none P0/P1.

## Tier 5. Performance / Concurrency

- PASS: This slice adds no blocking runtime elector yet; no production loop,
  scheduler, or renewal thread is introduced.
- PASS: `renewDelay()` renews before TTL expiry and guards the Consul TTL range.
- PASS: No new dependency is introduced for runtime client behavior; the future
  runtime slice can use Java 21 HttpClient behind the internal boundary.

Findings: none P0/P1.

## Tier 6. API Surface / Publishing

- PASS: Publishable module is included in the root settings and BOM.
- PASS: Public API surface is limited to DTO/options; session id, renewal, and
  lock client remain internal.
- PASS: Docs label the backend as Preview and state that runtime electors are
  still in progress.

Findings: none P0/P1.

## Gate

- P0: 0
- P1: 0
- Gate: PASS for PR creation after standard verification.

Validation evidence:

- `./gradlew :bluetape4k-leader-consul:test --no-daemon --console=plain`
  passed after the security fix.
- Claude Code advisor artifact
  `.omx/artifacts/claude-consul-slice1-code-review-final-20260522222921.md`
  reported P0=0, P1=0, Gate PASS before the local security hardening.
