# Strategic Election 예제

[English](README.md) | 한국어

Leader-only body를 실행하기 전에 가장 적합한 service node를 선택하는 backend-neutral strategic leader-election 예제입니다.

## 시나리오

세 service node가 maintenance task 실행 권한을 경쟁합니다. Readiness, historical success rate, idle time을 가중 점수로
합산해 winner를 선택합니다. Winner가 아닌 node는 예외 없이 action을 skip합니다.

## 아키텍처 다이어그램

![Strategic Election Architecture diagram](../../docs/images/readme-diagrams/examples-strategic-election-architecture-01.png)

## 시퀀스 다이어그램

![Strategic Election Sequence Flow diagram](../../docs/images/readme-diagrams/examples-strategic-election-sequence-01.png)

## 보여주는 내용

- Candidate readiness, reliability, fairness input을 조합합니다.
- `CandidateScorer`와 `WeightedScorer`로 normalized score를 계산합니다.
- `ScoredElectionStrategy`로 winner를 선택합니다.
- 외부 backend 없이 `LocalStrategicLeaderElector`로 작업을 실행합니다.
- Winner가 아닌 node에는 skip 결과를 반환합니다.

## 실행

```bash
./gradlew :examples:strategic-election:run
```

## 테스트

```bash
./gradlew :examples:strategic-election:test
```

## 설계

```kotlin
val scorer = WeightedScorer(
    ServiceReadinessScorer to 0.50,
    SuccessRateScorer to 0.35,
    IdleTimeScorer to 0.15,
)

val strategy = ScoredElectionStrategy(scorer)
val elector = LocalStrategicLeaderElector("node-a")
```

단순히 먼저 lock을 잡은 node가 아니라 가장 건강하거나 적합한 candidate를 leader로 고르고 싶을 때 이 예제를 참고합니다.
