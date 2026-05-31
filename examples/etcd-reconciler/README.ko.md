# etcd Reconciler 예제

[English](README.md) | 한국어

`EtcdLeaderElector`로 한 control-plane 노드만 reconcile loop를 소유하는
실행 가능한 etcd v3 예제입니다.

## 시나리오

여러 control-plane 노드가 하나의 etcd lock을 공유합니다. 선출된 노드만
desired state를 적용하고, 경쟁 노드는 해당 cycle을 skip합니다. 현재 리더가
lease를 해제하면 다른 노드가 같은 lock을 다시 획득할 수 있습니다.

## 보여주는 내용

- reconciler lock에 대해 etcd-backed leader lease 획득
- leader-only control-plane 작업 실행
- contention 시 예외 없이 non-leader reconcile attempt skip
- 작업 완료 후 leadership release
- 다른 노드의 lock reacquire

## 실행

예제는 `EtcdServer.Launcher.etcd`로 실제 etcd container를 시작하므로
Docker가 필요합니다.

```bash
./gradlew :examples:etcd-reconciler:run
```

## 테스트

```bash
./gradlew :examples:etcd-reconciler:test
```

테스트는 같은 lock을 사용하는 두 reconciler를 시작해 첫 lease가 활성인 동안
정확히 한 노드만 resource를 적용하는지 확인하고, release 이후 두 번째 노드가
lock을 다시 획득할 수 있음을 검증합니다.

## 설계

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

운영 애플리케이션은 자체 etcd endpoint, TLS, 인증 설정으로 jetcd `Client`를
생성해야 합니다. Client lifecycle은 호출자가 소유합니다.
