# etcd Reconciler 예제

[English](README.md) | 한국어

`EtcdLeaderElector`로 한 control-plane node만 reconcile loop를 소유하는 실행 가능한 etcd v3 예제입니다.

## 시나리오

여러 control-plane node가 같은 reconcile trigger를 받습니다. 각 node는 같은 etcd-backed lock을 획득하려고 하지만,
선출된 node만 desired resource list를 적용합니다. 다른 node는 해당 cycle을 skip하고, lease가 release되면 leadership이
다른 node로 이동할 수 있습니다.

## 아키텍처 다이어그램

![etcd Reconciler Architecture diagram](../../docs/images/readme-diagrams/examples-etcd-reconciler-architecture-01.png)

## 시퀀스 다이어그램

![etcd Reconciler Sequence Flow diagram](../../docs/images/readme-diagrams/examples-etcd-reconciler-sequence-01.png)

## 보여주는 내용

- Reconciler lock에 대해 etcd-backed leader lease를 획득합니다.
- Leader-only control-plane 작업을 실행합니다.
- Lock 경쟁에서 진 non-leader reconcile attempt는 예외 없이 skip합니다.
- Lease를 보유한 동안 desired resource를 idempotent하게 적용합니다.
- Release 이후 다른 node가 leadership을 다시 획득할 수 있습니다.

## 실행

예제는 `EtcdServer.Launcher.etcd`로 etcd container를 시작하므로 Docker가 필요합니다.

```bash
./gradlew :examples:etcd-reconciler:run
```

## 테스트

```bash
./gradlew :examples:etcd-reconciler:test
```

## 설계

```kotlin
val reconciler = ControlPlaneReconciler(
    nodeId = "control-plane-a",
    client = client,
    lockName = "control-plane-reconcile",
)

val report = reconciler.reconcile {
    listOf("deployment/api", "configmap/routing", "service/api")
}
```

운영 애플리케이션은 etcd endpoint, credential, timeout, network policy로 jetcd `Client`를 생성해야 합니다.
etcd lifecycle은 호출자가 소유합니다.
