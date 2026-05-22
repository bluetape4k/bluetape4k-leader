# Issue 227 Leader Etcd

## Context

Issue #227 is an epic for a full etcd v3 leader election backend. This branch
started the implementation with durable design artifacts, module registration,
the first internal jetcd boundary types, public single-leader electors, public
group electors, and a watch-backed event publisher.

## Decision

Keep the first PR narrow: add the `leader-etcd` module, version the project
against the active `bluetape4k-projects` `1.8.1-SNAPSHOT`, prove the low-level
lease/lock boundary, then add the first public single-leader electors while
adding group election and watch-backed event publishing as bounded follow-up
slices while deferring Spring Boot auto-configuration.

Use `bluetape4k-testcontainers` `EtcdServer.Launcher.etcd` for real integration
tests. Mock-based jetcd tests are useful for boundary delegation, but they do
not prove server endpoint wiring, lease keepalive, or etcd lock queueing.

## Outcome

- Added `leader-etcd` to settings and the leader BOM.
- Added etcd option models, key/path encoding, lease timing/handle helpers,
  backend error classification, and the `JetcdEtcdLockClient` boundary.
- Added blocking, coroutine, and virtual-thread single-leader electors backed
  by jetcd Lock leases.
- Added blocking and coroutine group electors backed by one jetcd Lock key per
  slot.
- Added `EtcdLeaderElectionEventPublisher`, which watches the configured prefix
  and maps etcd ownership key `PUT`/`DELETE` events to `Elected`/`Revoked`.
- Added active-lock extension support through the existing `LockExtender`
  contract.
- Made lock acquisition timeout cleanup cancel pending jetcd lock futures before
  lease revoke so timed-out contenders do not remain queued behind the holder.
- Added module README pairs and wired `leader-etcd` into CI and Nightly module
  coverage.
- Added unit tests for helper contracts.
- Added real EtcdServer-backed integration tests matching the mock lock-client
  coverage plus contention/release, cancellation cleanup, client extension,
  group max-leader bounds, slot reacquire, watch event delivery, and
  active-lock extension behavior.

## Verification

- `./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-testcontainers --configuration testRuntimeClasspath --refresh-dependencies --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:test --no-daemon --console=plain`
- `git diff --check`

Latest full test run executed 59 `leader-etcd` tests: 31 pure unit tests, 3
mocked jetcd boundary tests, and 25 real EtcdServer integration tests, including
watch publisher coverage for single/group event delivery, queued contender
suppression, and caller-owned client lifecycle.

## Future Guard

Do not treat mocked jetcd tests as sufficient for backend behavior. For every
public etcd elector added later, include a matching EtcdServer-backed test that
proves contention returns the leader contract result, lease expiry recovers
ownership, and cleanup does not depend on local process state.

Group state currently reads the lowest `createRevision` Lock key per slot so
queued contenders are not counted as active leaders, then falls back to the
ownership token as audit identity. Add richer sidecar metadata before claiming
human-readable group state from etcd.

The watch publisher intentionally emits only backend state changes. Do not
expect it to emit `Skipped`; skipped attempts are local acquisition outcomes and
remain covered by listener/decorator APIs.

jetcd Lock creates keys for queued contenders too. Watch publishers must
revalidate the current lowest-`createRevision` owner before emitting `Elected`,
or contention can produce false-positive election events.
