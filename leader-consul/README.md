# bluetape4k-leader-consul

[한국어](./README.ko.md) | English

Preview Consul backend for `bluetape4k-leader`.

This module provides preview single-leader and multi-leader group electors backed
by Consul sessions and KV `acquire`/`release`. Blocking, `CompletableFuture`,
coroutine, and Spring Boot auto-configuration surfaces are available.

## Behavior / Contract

- Public APIs use bluetape4k-owned DTOs such as `ConsulEndpoint`; no stale
  third-party Consul client type is exposed.
- Consul Session TTL must be between 10 seconds and 86,400 seconds.
- Single-leader keys use `keyPrefix/single/{encodedLockName}`.
- Group keys use fixed slots: `keyPrefix/group/{encodedLockName}/slot-{index}`.
- `lockDelay` defaults to zero for predictable scheduler-style reacquire.
- A zero lock delay can overlap an old holder still running after TTL expiry;
  actions should be idempotent or use an external fencing token when duplicate
  execution is unsafe.
- Consul endpoint, ACL token, datacenter, and agent lifecycle are caller-owned.
- Core event decorators such as `withListeners()` work with Consul electors.
  A backend-native Consul blocking-query watch publisher is intentionally not
  created by auto-configuration because Consul watch lifetime, backoff, and ACL
  scope are application-owned operational choices.

## Usage

```kotlin
val elector = ConsulLeaderElector(
    endpoint = ConsulEndpoint("http://localhost:8500"),
    options = ConsulLeaderElectionOptions(
        leaderOptions = LeaderElectionOptions(leaseTime = 10.seconds),
    ),
)

val result = elector.runIfLeader("daily-report") {
    "executed"
}
```

```kotlin
val suspendElector = ConsulSuspendLeaderElector(
    endpoint = ConsulEndpoint("http://localhost:8500"),
    options = ConsulLeaderElectionOptions(
        leaderOptions = LeaderElectionOptions(leaseTime = 10.seconds),
    ),
)

val suspendResult = suspendElector.runIfLeader("daily-report") {
    "executed"
}
```

```kotlin
val groupElector = ConsulLeaderGroupElector(
    endpoint = ConsulEndpoint("http://localhost:8500"),
    options = ConsulLeaderGroupElectionOptions(
        leaderGroupOptions = LeaderGroupElectionOptions(
            maxLeaders = 3,
            leaseTime = 10.seconds,
        ),
    ),
)

val groupResult = groupElector.runIfLeader("partition-workers") {
    "executed"
}
```

## Spring Boot

Register a caller-owned `ConsulEndpoint` bean. Auto-configuration then creates
`ConsulLeaderElector`, `ConsulSuspendLeaderElector`, `ConsulLeaderGroupElector`,
and `ConsulSuspendLeaderGroupElector` beans.

```yaml
bluetape4k:
  leader:
    lease-time: 10s
    group:
      max-leaders: 3
      lease-time: 10s
    consul:
      key-prefix: apps/orders/leader
      session-name-prefix: orders-leader
      lock-delay: 0s
```

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-consul:$bluetape4kLeaderVersion")
}
```
