# K3s Lease Leader Election Example

[한국어](README.ko.md) | English

This example demonstrates leader election with the Kubernetes
`coordination.k8s.io/v1` Lease API against a real K3s API server started by
`K3sServer.Launcher.k3s`.

## Scenario

Two holder identities compete for the same Kubernetes Lease. The first holder
creates or renews the Lease, a competing holder receives `CONFLICT` while the
Lease is still valid, and the current holder can release it so another holder
can acquire the same Lease later.

## Architecture Diagram

![k8s lease Architecture diagram](../../docs/images/readme-diagrams/examples-k8s-lease-architecture-01.png)

## Sequence Diagram

![k8s lease Sequence Flow diagram](../../docs/images/readme-diagrams/examples-k8s-lease-sequence-01.png)

## What It Shows

- Acquire a Lease when no holder exists.
- Reject a competing holder while the Lease is still valid.
- Release the Lease when the current holder owns it.
- Reacquire the Lease from another holder after release.

## Run

K3s requires Docker privileged mode. The test is tagged `k8s` and is excluded
from the normal `test` task.

```bash
./gradlew :examples:k8s-lease:k8sTest
```

Use this only on a local Docker daemon or CI runner that supports privileged
containers.

## Design

The example uses fabric8 typed Lease models:

```kotlin
val k3s = K3sServer.Launcher.k3s
k3s.kubernetesClient().use { client ->
    val election = K8sLeaseLeaderElectionExample(client)
    val acquired = election.tryAcquire("api-controller", "node-a")
}
```

This is an example-level Lease workflow, not a publishable `leader-k8s` backend.
