# etcd Reconciler Example

[한국어](README.ko.md) | English

Runnable etcd v3 example where one control-plane node owns the reconcile loop
through `EtcdLeaderElector`.

## Scenario

Multiple control-plane nodes share one etcd lock. The elected node applies the
desired state, competing nodes skip the cycle, and another node can acquire the
same lock after the current leader releases it.

## What It Shows

- Acquire an etcd-backed leader lease for a reconciler lock.
- Run leader-only control-plane work.
- Skip non-leader reconcile attempts without throwing on contention.
- Release leadership after the body completes.
- Reacquire the lock from another node.

## Run

The example starts a real etcd container through
`EtcdServer.Launcher.etcd`, so Docker is required.

```bash
./gradlew :examples:etcd-reconciler:run
```

## Test

```bash
./gradlew :examples:etcd-reconciler:test
```

The test starts two reconcilers against the same lock, verifies that only one
node applies resources while the first lease is active, then verifies that the
second node can reacquire the lock after release.

## Design

```kotlin
EtcdServer.Launcher.etcd.also { etcd ->
    Client.builder()
        .endpoints(etcd.endpoint)
        .build()
        .use { client ->
            val reconciler = ControlPlaneReconciler(
                nodeId = "control-plane-a",
                client = client,
                lockName = "control-plane-reconcile",
            )

            reconciler.reconcile {
                listOf("deployment/api", "configmap/routing", "service/api")
            }
        }
}
```

Production applications should create the jetcd `Client` from their own etcd
endpoints, TLS, and authentication configuration. The client lifecycle remains
caller-owned.
