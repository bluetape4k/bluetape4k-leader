# Redisson Watchdog 예제

[English](README.md)

이 예제는 bluetape4k lease auto-extension으로 보호되는 Redisson 기반 장시간
leader-only job을 보여줍니다. 한 노드가 초기 lease time보다 오래 리더 락을
유지하는 동안 경쟁 노드는 skip하고, 리더가 해제한 뒤에는 경쟁 노드가 다시
락을 획득할 수 있습니다.

## 시나리오

초기 lease 예산보다 오래 실행될 수 있는 Redis 기반 작업에 이 패턴을 사용합니다:

- `LeaderElectionOptions(autoExtend = true)` 설정;
- non-leader가 빨리 skip하도록 짧은 `waitTime` 사용;
- `leaseTime`은 최대 job runtime이 아니라 일반적인 갱신 주기에 맞춰 설정;
- leader action은 서비스 종료 또는 job timeout 정책으로 bounded 상태 유지.

현재 Redisson elector는 명시적 lease time으로 락을 획득하고 bluetape4k
`LeaderLeaseAutoExtender`가 lease를 갱신합니다. Redisson의 implicit
lock-watchdog 획득 모드에 의존하지 않으므로, 애플리케이션에서 둘 다 필요하다면
`autoExtend=true`와 `minLeaseTime > 0` 조합도 유효합니다.

## 실행

```bash
./gradlew :examples:redisson-watchdog:run
```

데모는 Testcontainers로 Redis를 시작하므로 Docker가 필요합니다.

## 테스트

```bash
./gradlew :examples:redisson-watchdog:test
```

핵심 테스트는 `leaseTime = 250.milliseconds`인 leader job을 시작한 뒤 초기
lease보다 오래 기다립니다. 그 동안 경쟁 노드가 계속 skip하는지 확인하고,
리더가 해제한 뒤에는 경쟁 노드가 다시 leadership을 획득하는지 검증합니다.

## 주요 API

- `RedissonLeaderElector`
- `LeaderElectionOptions(autoExtend = true)`
- `LeaderLeaseAutoExtender`

코루틴 네이티브 서비스라면 suspend Redisson elector를 우선 사용하세요. 이 blocking
예제는 동기 worker, scheduler callback, migration 코드처럼 이미 blocking thread에서
실행되는 작업에 적합합니다.
