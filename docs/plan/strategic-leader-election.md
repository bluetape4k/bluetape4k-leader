# Plan — Strategic Leader Election

> 작성: 2026-04-30
> 참조 Spec: docs/spec/strategic-leader-election.md

---

## 구현 순서

의존 관계 순서로 bottom-up 구현.

| # | 파일 | 의존 |
|---|------|------|
| 1 | `CandidateResult.kt` | 없음 |
| 2 | `CandidateInfo.kt` | `CandidateResult` |
| 3 | `CandidateScorer.kt` | `CandidateInfo` |
| 4 | `ElectionStrategy.kt` | `CandidateInfo` |
| 5 | `IdleTimeScorer.kt` | `CandidateScorer` |
| 6 | `SuccessRateScorer.kt` | `CandidateScorer` |
| 7 | `RecentSuccessScorer.kt` | `CandidateScorer` |
| 8 | `WeightedScorer.kt` | `CandidateScorer` |
| 9 | `FifoElectionStrategy.kt` | `ElectionStrategy` |
| 10 | `RandomElectionStrategy.kt` | `ElectionStrategy` |
| 11 | `ScoredElectionStrategy.kt` | `ElectionStrategy`, `CandidateScorer` |
| 12 | `StrategicLeaderElection.kt` | `CandidateInfo`, `ElectionStrategy`, `CandidateResult`, `LeaderElectionOptions` |
| 13 | `StrategicSuspendLeaderElection.kt` | 위와 동일 (suspend 버전) |
| 14 | `LocalStrategicLeaderElection.kt` | `StrategicLeaderElection` |
| 15 | `LocalStrategicSuspendLeaderElection.kt` | `StrategicSuspendLeaderElection` |
| T1 | `ElectionStrategyTest.kt` | 전략 3종 단위 테스트 |
| T2 | `CandidateScorerTest.kt` | Scorer 4종 단위 테스트 |
| T3 | `LocalStrategicLeaderElectionTest.kt` | Local 구현 통합 테스트 |
| T4 | `LocalStrategicSuspendLeaderElectionTest.kt` | suspend 버전 통합 테스트 |

---

## 설계 결정 사항

### 1. `StrategicLeaderElection` — 기존 인터페이스와 독립 계층

`LeaderElection`을 extend하지 않는다. 선출 방식이 근본적으로 다르므로(락 경쟁 vs 후보 목록 기반) 별도 계층으로 분리.

### 2. `LocalStrategicLeaderElection` — 동기화 전략

- 후보 맵: `ConcurrentHashMap<String, ConcurrentHashMap<String, CandidateInfo>>`
  - 외부 키: `lockName`, 내부 키: `nodeId`
- `runIfLeader()` 내부: `reentrantLock()` 으로 전체 시퀀스(listCandidates → selectLeader → run) atomic 보장

### 3. `updateResult()` — 불변 CandidateInfo 업데이트

`CandidateInfo`는 data class(불변). `updateResult()` 는 기존 `CandidateInfo`를 `copy()`로 업데이트 후 맵 교체.

### 4. `RandomElectionStrategy` — 결정론적 처리

로컬 pilot 구현에서는 seed 파라미터 제공. 분산 환경에서의 shared seed는 백엔드 구현 시 처리.

### 5. Tie-breaking

동점 후보 발생 시 `registeredAt` 오름차순(먼저 등록한 쪽) 으로 결정. 모든 전략에 공통 적용.

---

## 파일 트리

```
leader-core/src/main/kotlin/io/bluetape4k/leader/
├── strategy/
│   ├── CandidateInfo.kt
│   ├── CandidateResult.kt
│   ├── ElectionStrategy.kt
│   ├── CandidateScorer.kt
│   ├── strategies/
│   │   ├── FifoElectionStrategy.kt
│   │   ├── RandomElectionStrategy.kt
│   │   └── ScoredElectionStrategy.kt
│   └── scorers/
│       ├── IdleTimeScorer.kt
│       ├── SuccessRateScorer.kt
│       ├── RecentSuccessScorer.kt
│       └── WeightedScorer.kt
├── StrategicLeaderElection.kt
├── coroutines/
│   └── StrategicSuspendLeaderElection.kt
└── local/
    ├── LocalStrategicLeaderElection.kt
    └── LocalStrategicSuspendLeaderElection.kt
```

---

## 테스트 시나리오 (주요)

### ElectionStrategyTest

- `FifoElectionStrategy`: 3명 후보 중 가장 먼저 등록된 후보 선출
- `RandomElectionStrategy`: 동일 seed → 동일 결과
- `ScoredElectionStrategy(IdleTimeScorer)`: idle 시간 가장 긴 후보 선출
- `ScoredElectionStrategy(WeightedScorer)`: weight 조합 최고점 선출
- 후보 0명: `null` 반환
- 후보 1명: 해당 후보 반환

### LocalStrategicLeaderElectionTest

- 3개 노드 동시 `runIfLeader()`: 정확히 1개만 action 실행
- `updateResult(SUCCESS)` 후 `successCount` 증가 확인
- `updateResult(FAILURE)` 후 `failureCount` 증가 확인
- `FifoElectionStrategy` 적용: 가장 먼저 등록된 노드가 winner
- `ScoredElectionStrategy(IdleTimeScorer)` 적용: idle 가장 긴 노드 winner
