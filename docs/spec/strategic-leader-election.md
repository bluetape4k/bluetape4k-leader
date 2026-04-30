# Spec — Strategic Leader Election

> 작성: 2026-04-30
> 이슈: #29 (예정)
> 상태: Draft

---

## 1. 배경 및 목적

현재 `LeaderElection` 구현체들은 분산 락(FIFO) 방식으로 리더를 선출한다. 이 방식은 단순하지만 다음 시나리오에서 한계가 있다.

- **부하 분산**: 항상 동일 노드가 리더가 되면 특정 노드에 부하 집중
- **복원력(Resilience)**: 최근 실패한 노드보다 성공한 노드를 리더로 선호해야 하는 경우
- **공정성**: 오래 쉰 노드에게 우선권을 주어 작업 기회 균등 분배

이 기능은 **플러그형 선출 전략(Pluggable Election Strategy)** 을 leader-core 에 추가하여 다양한 선출 기준을 지원한다.

---

## 2. 범위

### In-scope

- `CandidateInfo` — 후보 노드 메타데이터 (leader-core)
- `ElectionStrategy` — 선출 전략 인터페이스 (leader-core)
- `CandidateScorer` — 점수화 인터페이스 (leader-core)
- 내장 Scorer 구현체 4종 (leader-core)
- `ScoredElectionStrategy`, `FifoElectionStrategy`, `RandomElectionStrategy` (leader-core)
- `StrategicLeaderElection` / `StrategicSuspendLeaderElection` — 새 인터페이스 (leader-core)
- `LocalStrategicLeaderElection` / `LocalStrategicSuspendLeaderElection` — 인메모리 pilot 구현체 (leader-core)
- 단위 테스트 (leader-core)

### Out-of-scope (별도 이슈)

- Redis/Exposed/MongoDB 백엔드의 `StrategicLeaderElection` 구현
- 분산 CandidateRegistry (백엔드별 저장소)
- Heartbeat 데몬 관리 유틸리티
- `AsyncStrategicLeaderElection` / `VirtualThreadStrategicLeaderElection` 변형 (후순위)

---

## 3. 선출 흐름

```
runIfLeader() 호출
  ↓
ensureRegistered(lockName, candidateInfo)   ← 후보 등록 (없으면 신규, 있으면 TTL 갱신)
  ↓
listCandidates(lockName)                    ← 현재 후보 목록 조회
  ↓
strategy.selectLeader(candidates)          ← 전략 적용 → 1명 선출
  ↓
winner.nodeId == myNodeId ?
  YES → run action() → updateResult()     ← 작업 실행 + 결과 기록
  NO  → return null                        ← drop (skip)
```

> **Local pilot scope**: 단일 프로세스 내 `reentrantLock()` 으로 listCandidates→selectLeader→run 시퀀스 atomic 보장.  
> 분산 환경에서는 노드별 등록/조회 시점 차이로 후보 목록이 달라져 winner 불일치 가능.  
> **분산 일관성 보장(epoch/coordinator 패턴)은 백엔드 구현 시 처리한다.**

단, `RandomElectionStrategy` 사용 시 분산 환경에서 shared seed 필요 (백엔드 구현 시 처리).

---

## 4. 데이터 모델

### CandidateInfo

```kotlin
data class CandidateInfo(
    val nodeId: String,                          // 후보 노드 식별자 (UUID 권장)
    val registeredAt: Instant = Instant.now(),   // 후보 등록 시각
    val lastStartTime: Instant? = null,          // 마지막 작업 시작 시각
    val lastCompletionTime: Instant? = null,     // 마지막 작업 완료 시각
    val successCount: Long = 0,                  // 누적 성공 횟수
    val failureCount: Long = 0,                  // 누적 실패 횟수
    val metadata: Map<String, String> = emptyMap(), // 확장 메타데이터
) {
    /** 마지막 완료 이후 경과 시간. 완료 이력 없으면 등록 시각부터 계산 (null 없음). */
    val idleDuration: Duration
        get() = lastCompletionTime?.let { Duration.between(it, Instant.now()) }
                ?: Duration.between(registeredAt, Instant.now())
    val successRate: Double
        get() = if (successCount + failureCount == 0L) 0.0
                else successCount.toDouble() / (successCount + failureCount)
    val totalCount: Long get() = successCount + failureCount
}
```

### CandidateResult

```kotlin
enum class CandidateResult { SUCCESS, FAILURE }
```

---

## 5. 인터페이스 설계

### ElectionStrategy

```kotlin
interface ElectionStrategy {
    fun selectLeader(candidates: List<CandidateInfo>): CandidateInfo?
}
```

### CandidateScorer

```kotlin
interface CandidateScorer {
    fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double
}
```

### StrategicLeaderElection

```kotlin
interface StrategicLeaderElection {
    val nodeId: String

    /**
     * 후보 등록 (없으면 신규 등록, 있으면 정보 갱신).
     * [ttl] = `Duration.ZERO` 이면 TTL 없음 (Local 구현은 무시).
     * 분산 백엔드 구현 시 heartbeat 주기의 2배 이상으로 설정 권장.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /** 등록 해제 */
    fun unregisterCandidate(lockName: String, nodeId: String)

    /** 현재 후보 목록 조회 */
    fun listCandidates(lockName: String): List<CandidateInfo>

    /** 선출 후 결과 갱신 */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * 전략으로 리더 선출 후 winner 만 action 실행.
     * 선출 실패(후보 없음) 또는 다른 노드가 winner 이면 null 반환.
     */
    fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T?
}
```

### StrategicSuspendLeaderElection

`StrategicLeaderElection` 의 suspend 버전 — 동일 구조, 모든 메서드 suspend.

---

## 6. 내장 전략 목록

| 클래스 | 설명 | 동작 |
|--------|------|------|
| `FifoElectionStrategy` | 가장 먼저 등록한 후보 선출 | `registeredAt` 오름차순 첫 번째 |
| `RandomElectionStrategy` | 랜덤 선출 | 주어진 seed 또는 시스템 랜덤 |
| `ScoredElectionStrategy` | 점수 기반 선출 | `scorer.score()` 최고점 후보 |

---

## 7. 내장 Scorer 목록

| 클래스 | 선호 후보 | 점수 산식 |
|--------|----------|---------|
| `IdleTimeScorer` | 가장 오래 쉰 노드 | `idleDuration.toMillis()` (미실행 노드 = 등록 이후 전체 경과 시간) |
| `SuccessRateScorer` | 성공률 높은 노드 | `successRate * 100` |
| `RecentSuccessScorer` | 최근 성공한 노드 | 성공한 경우 최근 완료시각 점수 |
| `WeightedScorer` | 복합 기준 | `Σ(scorer.score * weight)` |

---

## 8. 파일 배치

```
leader-core/src/main/kotlin/io/bluetape4k/leader/
  strategy/
    CandidateInfo.kt
    CandidateResult.kt
    ElectionStrategy.kt
    CandidateScorer.kt
    strategies/
      FifoElectionStrategy.kt
      RandomElectionStrategy.kt
      ScoredElectionStrategy.kt
    scorers/
      IdleTimeScorer.kt
      SuccessRateScorer.kt
      RecentSuccessScorer.kt
      WeightedScorer.kt
  StrategicLeaderElection.kt
  coroutines/
    StrategicSuspendLeaderElection.kt
  local/
    LocalStrategicLeaderElection.kt          ← pilot 구현 (in-memory)
    LocalStrategicSuspendLeaderElection.kt   ← pilot suspend 구현

leader-core/src/test/kotlin/io/bluetape4k/leader/
  strategy/
    ElectionStrategyTest.kt
    CandidateScorerTest.kt
  local/
    LocalStrategicLeaderElectionTest.kt
    LocalStrategicSuspendLeaderElectionTest.kt
```

---

## 9. 제약 / 비기능 요구사항

- 모든 타입: Kotlin 2.3+, JVM 21 타겟
- `CandidateInfo`: `Serializable` 구현 (Redis 직렬화 호환)
- 스레드 안전: `LocalStrategicLeaderElection` 은 `ConcurrentHashMap` + `reentrantLock()` 사용
- `CancellationException` 재전파 보장 (suspend 구현체)
- 모든 공개 API: 한국어 KDoc

---

## 10. 수용 기준 (Acceptance Criteria)

- [ ] `FifoElectionStrategy` — 가장 먼저 등록한 후보가 선출됨
- [ ] `ScoredElectionStrategy(IdleTimeScorer)` — 가장 오래 쉰 후보가 선출됨
- [ ] `ScoredElectionStrategy(WeightedScorer)` — 복합 점수 최고 후보 선출됨
- [ ] `LocalStrategicLeaderElection.runIfLeader()` — winner 만 action 실행, 나머지 null 반환
- [ ] `updateResult()` 후 `successCount`/`failureCount` 반영됨
- [ ] 후보 0명인 경우 `selectLeader()` → null 반환
- [ ] 모든 테스트 통과
