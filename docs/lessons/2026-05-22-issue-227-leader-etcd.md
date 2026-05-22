# Issue 227 Leader Etcd

## Context

Issue #227 is an epic for a full etcd v3 leader election backend. This branch
started the implementation with durable design artifacts, module registration,
and the first internal jetcd boundary types.

## Decision

Keep the first PR narrow: add the `leader-etcd` module, version the project
against the active `bluetape4k-projects` `1.8.1-SNAPSHOT`, and prove the
low-level lease/lock boundary before adding public electors.

Use `bluetape4k-testcontainers` `EtcdServer.Launcher.etcd` for real integration
tests. Mock-based jetcd tests are useful for boundary delegation, but they do
not prove server endpoint wiring, lease keepalive, or etcd lock queueing.

## Outcome

- Added `leader-etcd` to settings and the leader BOM.
- Added etcd option models, key/path encoding, lease timing/handle helpers,
  backend error classification, and the `JetcdEtcdLockClient` boundary.
- Added unit tests for helper contracts.
- Added real EtcdServer-backed integration tests matching the mock lock-client
  coverage plus contention/release behavior.

## Verification

- `./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-testcontainers --configuration testRuntimeClasspath --refresh-dependencies --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:test --no-daemon --console=plain`
- `git diff --check`

Latest test run executed 38 `leader-etcd` tests: 31 pure unit tests, 3 mocked
jetcd boundary tests, and 4 real EtcdServer integration tests.

## Future Guard

Do not treat mocked jetcd tests as sufficient for backend behavior. For every
public etcd elector added later, include a matching EtcdServer-backed test that
proves contention returns the leader contract result, lease expiry recovers
ownership, and cleanup does not depend on local process state.
