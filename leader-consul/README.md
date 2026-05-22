# bluetape4k-leader-consul

[한국어](./README.ko.md) | English

Preview Consul backend for `bluetape4k-leader`.

This module starts with the public endpoint/options contract and the internal
Consul Session + KV HTTP boundary. The first runtime slice targets single-leader
blocking and coroutine electors backed by Consul sessions and KV
`acquire`/`release`.

## Behavior / Contract

- Public APIs use bluetape4k-owned DTOs such as `ConsulEndpoint`; no stale
  third-party Consul client type is exposed.
- Consul Session TTL must be between 10 seconds and 86,400 seconds.
- `lockDelay` defaults to zero for predictable scheduler-style reacquire.
- A zero lock delay can overlap an old holder still running after TTL expiry;
  actions should be idempotent or use an external fencing token when duplicate
  execution is unsafe.
- Consul endpoint, ACL token, datacenter, and agent lifecycle are caller-owned.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-consul:$bluetape4kLeaderVersion")
}
```
