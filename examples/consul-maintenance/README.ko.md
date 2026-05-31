# Consul Maintenance 예제

[English](README.md) | 한국어

`ConsulLeaderElector`로 한 service instance만 maintenance 또는 drain 작업을
소유하는 실행 가능한 Consul 예제입니다.

## 시나리오

여러 service instance가 하나의 Consul lock을 공유합니다. 선출된 instance만
maintenance step을 수행하고, 경쟁 instance는 해당 cycle을 skip합니다. 현재
리더가 lease를 해제하면 다른 instance가 같은 lock을 다시 획득할 수 있습니다.

## 보여주는 내용

- service-maintenance lock에 대해 Consul Session + KV leader lease 획득
- draining, endpoint rotation 같은 leader-only maintenance 작업 실행
- contention 시 예외 없이 non-leader maintenance attempt skip
- 작업 완료 후 leadership release
- 다른 service instance의 lock reacquire

## 실행

예제는 `ConsulServer.Launcher.consul`로 실제 Consul container를 시작하므로
Docker가 필요합니다.

```bash
./gradlew :examples:consul-maintenance:run
```

## 테스트

```bash
./gradlew :examples:consul-maintenance:test
```

테스트는 같은 lock을 사용하는 두 coordinator를 시작해 첫 lease가 활성인 동안
정확히 한 노드만 maintenance를 수행하는지 확인하고, release 이후 두 번째
노드가 lock을 다시 획득할 수 있음을 검증합니다.

## 설계

```kotlin
val endpoint = ConsulEndpoint(ConsulServer.Launcher.consul.url)
val coordinator = ServiceMaintenanceCoordinator(
    config = ServiceMaintenanceConfig(
        nodeId = MaintenanceNodeId("checkout-a"),
        lockName = MaintenanceLockName("service-maintenance:checkout"),
    ),
    endpoint = endpoint,
)

coordinator.performMaintenance {
    listOf("mark-instance-draining", "flush-inflight-requests", "rotate-service-endpoint")
}
```

운영 애플리케이션은 자체 Consul HTTP API endpoint, datacenter, ACL token,
timeout, network policy로 `ConsulEndpoint`를 생성해야 합니다. Consul agent
lifecycle은 호출자가 소유합니다.
