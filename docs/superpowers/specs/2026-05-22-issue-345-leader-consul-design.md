# Issue 345 leader-consul Design

## Context

Issue #345 adds a Consul-backed leader election backend for deployments that
already run Consul for service discovery or dynamic configuration. Consul is a
secondary cloud-native coordination backend alongside ZooKeeper, Kubernetes
Lease, and etcd.

Consul's supported primitive is Session + KV locking:

- Sessions can be invalidated by explicit destroy, health checks, node removal,
  or TTL expiry.
- KV `acquire` succeeds only when no other session holds the key, and KV
  `release` clears the session only for the supplied session id.
- Consul locks are advisory; clients that bypass the lock can still modify the
  key.
- Session TTL has a documented minimum of 10 seconds and should be renewed
  before expiry to absorb skew and network delay.

References:

- https://developer.hashicorp.com/consul/docs/automate/session
- https://developer.hashicorp.com/consul/api-docs/session
- https://developer.hashicorp.com/consul/api-docs/libraries-and-sdks

## Goals

- Add `leader-consul` as a preview module.
- Support blocking and coroutine single-leader election first.
- Preserve the core contract: normal contention returns `null`, not an
  exception.
- Use Consul KV session acquire/release with caller-owned Consul client
  lifecycle.
- Add Testcontainers integration coverage through
  `ConsulServer.Launcher.consul`.
- Keep group election, Spring Boot auto-configuration, and event publishing as
  follow-up slices unless the first single-leader slice proves the client API is
  simple enough to extend safely.

## Non-Goals

- Do not implement service discovery, health registration, ACL management, or
  Consul agent lifecycle.
- Do not rely on Consul lock delay for the bluetape4k `minLeaseTime` contract.
- Do not expose a new global dependency from existing modules.
- Do not auto-create or close a Consul client in Spring integration.

## Dependency Decision

Use `com.orbitz.consul:consul-client` only for the initial implementation
slice if its current API still exposes Session and KV acquire/release cleanly.

Rationale:

- HashiCorp lists both `consul-client` and `consul-api` as Java HTTP API client
  libraries, but neither is official.
- `consul-client` has a newer Maven Central release (`1.5.3`, 2021) than
  `com.ecwid.consul:consul-api` (`1.4.5`, 2020).
- A direct HTTP implementation would avoid stale client transitive dependencies
  but would add more local HTTP/JSON surface area and testing burden.

If `consul-client` API friction is high during spike, fall back to a tiny
internal HTTP boundary over Java 21 `HttpClient` and `kotlinx.serialization` or
manual JSON DTOs. Keep that boundary internal so the public constructor can be
adapted without breaking users.

## Public API Shape

```kotlin
val consul = Consul.builder()
    .withUrl("http://localhost:8500")
    .build()

val election = ConsulLeaderElector(
    consul = consul,
    options = ConsulLeaderElectionOptions(
        keyPrefix = "bluetape4k/leader",
        leaderOptions = LeaderElectionOptions(
            nodeId = "worker-0",
            waitTime = 2.seconds,
            leaseTime = 15.seconds,
            autoExtend = true,
        ),
    ),
)

val result = election.runIfLeader("daily-report") {
    generateReport()
}
```

`ConsulSuspendLeaderElector` mirrors the blocking API and wraps blocking client
calls in `Dispatchers.IO` if the selected client has no coroutine API.

## Options

`ConsulLeaderElectionOptions`:

- `keyPrefix: String = "bluetape4k/leader"`: Consul KV key prefix. Consul keys
  are slash-separated but not absolute paths.
- `sessionNamePrefix: String = "bluetape4k-leader"`: prefix for created session
  names.
- `lockDelay: Duration = Duration.ZERO`: Consul session lock delay. Default is
  zero to keep skip/reacquire behavior predictable for job schedulers.
- `leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default`.

Validation:

- `leaseTime` must be at least 10 seconds for Consul Session TTL.
- `waitTime` can be shorter than `leaseTime`; contention polling should stop at
  the caller budget.
- `keyPrefix` must be non-blank and must not start with `/`.

## Lock Flow

Acquire:

1. Validate `lockName` and build key
   `{keyPrefix}/single/{percentEncoded(lockName)}`.
2. Create a Consul session with `Behavior=release`, `TTL=leaseTime`, and
   `LockDelay=lockDelay`.
3. Attempt KV acquire with that session id and an owner payload containing
   node id, audit id, and created-at timestamp.
4. If acquire succeeds, run the action and release in `finally`.
5. If acquire fails because another session owns the key, destroy the session
   and return `null`.
6. On backend errors, classify and follow existing backend exception/result
   conventions.

Release:

1. Release with the same session id.
2. Destroy the session best-effort.
3. Do not delete the key on normal release; leave audit payload for state
   inspection.

Auto-extend:

- Implement `LockExtender` by renewing the active session.
- `extendDetailed()` returns the new expiry estimate as
  `Instant.now() + leaseTime`; document that Consul TTL expiry is server-side
  and may be delayed.

## State Mapping

`state(lockName)` reads the KV key and maps:

- missing key or blank session -> empty state;
- active session -> occupied state using decoded owner payload if available;
- undecodable payload -> fallback to session id as audit identity.

## Test Requirements

Unit tests:

- key encoding and option validation;
- response/error classification;
- owner payload codec;
- mock boundary tests for session create, acquire, release, destroy, and renew.

Integration tests with `ConsulServer.Launcher.consul`:

- leader executes and releases for reacquire;
- contention returns `null`;
- expired session lets another node acquire;
- action failure releases the key/session for a later attempt;
- auto-extend keeps a long action held;
- caller-owned client is not closed by the elector.

## Follow-Up Slices

- Group election with independent slot keys:
  `{keyPrefix}/group/{encodedLockName}/slot-{n}`.
- Spring Boot auto-configuration when a caller-owned Consul client bean is
  present.
- Event publisher using Consul blocking queries over the key prefix.
- Rich management/state views that expose Consul `LockIndex`, `ModifyIndex`,
  and session id.
