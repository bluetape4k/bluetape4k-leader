# Redisson Watchdog 예제

[English](README.md) | 한국어

bluetape4k lease auto-extension으로 장시간 leader job을 보호하는 Redisson 기반 예제입니다.

## 시나리오

두 node가 같은 Redis-backed lock을 경쟁합니다. 선출된 node의 job은 초기 lease time보다 오래 실행될 수 있으므로
`LeaderElectionOptions(autoExtend = true)`가 method body 실행 중 lock을 계속 갱신합니다. 경쟁 node는 lock이 유지되는
동안 skip하고, leader가 release한 뒤 lock을 획득할 수 있습니다.

## 아키텍처 다이어그램

![Redisson Watchdog Architecture diagram](../../docs/images/readme-diagrams/examples-redisson-watchdog-architecture-01.png)

## 시퀀스 다이어그램

![Redisson Watchdog Sequence Flow diagram](../../docs/images/readme-diagrams/examples-redisson-watchdog-sequence-01.png)

## 보여주는 내용

- Redisson-backed leader job에 `LeaderElectionOptions(autoExtend = true)`를 설정합니다.
- Non-leader가 빨리 skip하도록 `waitTime`을 짧게 둡니다.
- `leaseTime`은 최대 job runtime이 아니라 renewal cadence에 맞춥니다.
- Leader body가 끝나면 Redisson lock을 release합니다.
- Leader body는 shutdown 또는 timeout policy로 bounded 상태를 유지합니다.

## 실행

기본 실행은 Redis Testcontainers launcher를 사용하므로 Docker가 필요합니다.

```bash
./gradlew :examples:redisson-watchdog:run
```

## 테스트

```bash
./gradlew :examples:redisson-watchdog:test
```

## 설계

```kotlin
val options = LeaderElectionOptions(
    waitTime = 200.milliseconds,
    leaseTime = 2.seconds,
    autoExtend = true,
)

val runner = RedissonWatchdogJobRunner("node-a", redissonClient, "nightly-export", options)

val report = runner.runJob {
    exportService.rollup()
}
```

실행 시간이 bounded하지만 변동 폭이 크고, 경쟁자가 기다리지 않고 skip해야 하는 job에 이 패턴을 사용합니다.
