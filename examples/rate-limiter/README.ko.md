# examples-rate-limiter

[English](README.md)

이 예제는 Redis leader election 과 distributed Bucket4j rate limiter 를 함께
사용한다.

## 시나리오

3개 애플리케이션 노드가 같은 외부 API workload 를 dispatch 하려고 시도한다.
Redis leader election 은 단 1개 노드만 workload 를 스케줄링하게 한다. 이후
스케줄된 work item 은 모든 노드가 하나의 Redis-backed Bucket4j quota 를 공유해
소비한다.

- `SCHEDULED`: leader election 에 성공해 work item 을 만든 노드.
- `CONSUMED`: distributed token 을 소비하고 external API probe 를 호출한 worker.
- `REJECTED`: scheduler 가 아니거나 distributed quota 에서 거절된 호출.

테스트는 3초 window 동안 초당 10회 quota 를 사용하며, 전체 외부 API 호출 수가
`30` 이하인지 검증한다.

## 아키텍처 다이어그램

![rate limiter Architecture diagram](../../docs/images/readme-diagrams/examples-rate-limiter-architecture-01.png)

## 시퀀스 다이어그램

![rate limiter Sequence Flow diagram](../../docs/images/readme-diagrams/examples-rate-limiter-sequence-01.png)

## 실행

```bash
./gradlew :examples:rate-limiter:run
```

데모는 `bluetape4k-testcontainers` 의 `RedisServer.Launcher.redis` 를 자동으로
시작한다. Docker 가 필요하다.

## 테스트

```bash
./gradlew :examples:rate-limiter:test
```

테스트는 다음을 검증한다.

- 3개 노드 중 정확히 1개만 workload 를 dispatch 한다.
- distributed worker 호출이 하나의 Redis Bucket4j quota 를 공유한다.
- 전체 외부 API 호출 수가 3초 window 의 기대 상한을 넘지 않는다.
