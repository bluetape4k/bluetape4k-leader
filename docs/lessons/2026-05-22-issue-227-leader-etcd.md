# Issue 227 Leader Etcd

## Context

Issue #227 is an epic for a full etcd v3 leader election backend. This branch
started the implementation with durable design artifacts, module registration,
the first internal jetcd boundary types, and public single-leader electors.

## Decision

Keep the first PR narrow: add the `leader-etcd` module, version the project
against the active `bluetape4k-projects` `1.8.1-SNAPSHOT`, prove the low-level
lease/lock boundary, then add the first public single-leader electors while
deferring group election, watch APIs, and Spring Boot auto-configuration.

Use `bluetape4k-testcontainers` `EtcdServer.Launcher.etcd` for real integration
tests. Mock-based jetcd tests are useful for boundary delegation, but they do
not prove server endpoint wiring, lease keepalive, or etcd lock queueing.

## Outcome

- Added `leader-etcd` to settings and the leader BOM.
- Added etcd option models, key/path encoding, lease timing/handle helpers,
  backend error classification, and the `JetcdEtcdLockClient` boundary.
- Added blocking, coroutine, and virtual-thread single-leader electors backed
  by jetcd Lock leases.
- Added active-lock extension support through the existing `LockExtender`
  contract.
- Made lock acquisition timeout cleanup cancel pending jetcd lock futures before
  lease revoke so timed-out contenders do not remain queued behind the holder.
- Added module README pairs and wired `leader-etcd` into CI and Nightly module
  coverage.
- Added unit tests for helper contracts.
- Added real EtcdServer-backed integration tests matching the mock lock-client
  coverage plus contention/release, cancellation cleanup, client extension, and
  active-lock extension behavior.

## Verification

- `./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-testcontainers --configuration testRuntimeClasspath --refresh-dependencies --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:test --no-daemon --console=plain`
- `git diff --check`

Latest test run executed 46 `leader-etcd` tests: 31 pure unit tests, 3 mocked
jetcd boundary tests, and 12 real EtcdServer integration tests.

## Future Guard

Do not treat mocked jetcd tests as sufficient for backend behavior. For every
public etcd elector added later, include a matching EtcdServer-backed test that
proves contention returns the leader contract result, lease expiry recovers
ownership, and cleanup does not depend on local process state.
