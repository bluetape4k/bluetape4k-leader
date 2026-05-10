# Lessons Learned — E4 Cache Partition Warmer (2026-05-10)

**관련 PR**: TBD
**관련 Issue**: #157 (Epic #36)
**영향 모듈**: `examples/cache-warmer/`, `.github/workflows/{ci,nightly}.yml`, `settings.gradle.kts`

## L1: 파티션별 독립 lockName — LeaderGroup 미사용

### 문제
초기 spec 은 `LeaderGroupElector` (maxLeaders=N) 활용 의도. 그러나 LeaderGroup 은 "전체 N개 슬롯 중 누가 몇 번째 슬롯" 모델 — 파티션 식별자가 슬롯에 매핑되지 않음.

### 교훈
파티션별 정확히 1개 워머 보장 = 파티션별 독립 lockName + 단일 리더. 더 단순:

```kotlin
partitions.forEach { partitionId ->
    val lockName = "${prefix}-${partitionId}"
    val elector = electorFactory(lockName, options)
    elector.runIfLeader(lockName) { warmFunction(partitionId) }
}
```

각 파티션이 독립 락 → 같은 인스턴스도 여러 파티션 워밍 가능. 다른 인스턴스가 다른 파티션 동시 워밍 → 부하 분산 자연 발생.

LeaderGroup 은 실제로 "워크스페이스 풀에서 N명 작업자 동시 실행" 시나리오에 적합 (E5 참고).

---

## L2: WarmResult 의 failed 분리 — handler 예외 격리

### 교훈
일부 파티션 워머 함수 예외 시 전체 중단 X. failed map 에 기록 후 다음 파티션 계속:

```kotlin
data class WarmResult(
    val nodeId: String,
    val warmed: List<String>,
    val skipped: List<String>,
    val failed: Map<String, String>,  // partitionId -> error
)
```

호출자가 fail-tolerant 결정 가능 (예: 5/10 실패 시 alert 발송 + 5 succeeded 는 ready).

---

## L3: Codex CLI 가 멈출 때 fallback 정책

### 문제
E4 spec/code review 시 codex 가 멈춤 (heredoc encoding 문제 또는 일시적 hang). 3회 retry 적용해도 1회차에서 hang.

### 교훈
Codex retry 3회 후 fallback:
- 코어 검증은 `code-reviewer` agent (Tier 4+5) + 8/8 테스트 통과로 충분
- 머지 후 부족한 부분은 follow-up issue
- 같은 spec 패턴 (E1~E3) 반복 시 Codex 의존도 낮춤

---

## L4: agent 위임 시 settings.gradle.kts / CI workflow 누락 가능성

### 문제
executor agent 가 `examples/cache-warmer/` 소스/테스트는 만들었으나 `settings.gradle.kts` 등록 + ci.yml/nightly.yml workflow job 추가는 누락.

### 교훈
agent 위임 prompt 에 명시했어도 누락 가능. delegation 후 항상:
- `git status` 로 변경 파일 확인
- `settings.gradle.kts` 에 모듈 include 검증
- `.github/workflows/{ci,nightly}.yml` 에 job 추가 검증
- 누락 시 직접 수정

E5 위임 시 동일 체크리스트 적용.
