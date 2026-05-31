# Strategic Election 예제

[English](README.md)

이 예제는 backend-neutral strategic leader election 시나리오를 보여줍니다.
세 service node가 maintenance task 실행 권한을 경쟁하고, service readiness,
historical success rate, idle time을 가중 점수로 합산해 winner를 선택합니다.

## 시나리오

maintenance window에서 가장 적합한 node 하나만 작업을 실행합니다:

- service readiness는 health와 available capacity를 조합합니다.
- historical success rate는 안정적으로 성공한 node를 우선합니다.
- idle time은 최근 사용되지 않은 node에 작은 fairness boost를 줍니다.
- winner가 아닌 node는 예외 없이 action을 skip합니다.

## 실행

```bash
./gradlew :examples:strategic-election:run
```

## 테스트

```bash
./gradlew :examples:strategic-election:test
```

## 핵심 API

- `LocalStrategicLeaderElector`
- `CandidateScorer`
- `WeightedScorer`
- `ScoredElectionStrategy`

candidate registry를 process 간 공유해야 한다면 같은 scoring pattern을
distributed strategic elector와 함께 사용할 수 있습니다.
