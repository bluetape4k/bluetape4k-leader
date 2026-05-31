# Virtual Thread Runner 예제

[English](README.md)

이 예제는 Java virtual thread 기반 고동시성 leader-only runner를 보여줍니다.
여러 service node가 하나의 local leader lock을 경쟁하고, 단 하나의 node만
virtual thread에서 bounded maintenance 작업을 실행하며 나머지는 예외 없이
skip합니다.

## 시나리오

blocking task가 많지만 maintenance window마다 하나의 leader-only action만
필요한 service에서 이 패턴을 사용할 수 있습니다:

- 각 node는 `VirtualThreadLeaderElector`로 leader 작업을 제출합니다.
- leader action은 Java virtual thread에서 실행됩니다.
- loser는 carrier thread를 점유하지 않고 skipped report를 반환합니다.
- demo는 leader action을 timeout으로 제한해 shutdown을 예측 가능하게 합니다.

## 실행

```bash
./gradlew :examples:virtual-thread-runner:run
```

Docker는 필요하지 않습니다. 예제는 local in-JVM leader backend를 사용합니다.

## 테스트

```bash
./gradlew :examples:virtual-thread-runner:test
```

## 핵심 API

- `VirtualThreadLeaderElector`
- `LocalVirtualThreadLeaderElector`
- `LeaderElectionOptions`

Java 21 virtual thread 이점을 얻는 blocking action에는 virtual-thread elector를
사용하세요. suspend-first application code에는 coroutine elector를, 단순 동기
service에는 blocking elector를 우선 고려하세요.
