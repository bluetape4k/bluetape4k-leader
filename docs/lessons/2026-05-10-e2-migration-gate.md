# Lessons Learned — E2 Migration Gate (2026-05-10)

**관련 PR**: TBD
**관련 Issue**: #155 (Epic #36)
**영향 모듈**: `examples/migration-gate/`, `.github/workflows/{ci,nightly}.yml`, `settings.gradle.kts`

## L1: spec/plan 단계 Codex 사전 리뷰 효과

### 문제
Codex spec 검토에서 CRITICAL 3건 + HIGH 11건 발견. 단순 `runIfLeader == null → Followed` 매핑 모델로 시작했으나 운영 안전성 측면 결함 다수.

### 교훈
spec → 코드 작성 직전에 Codex 의뢰 1회는 비용 대비 효과 매우 큼. 이번 사례에서 다음 변경 견인:
- `Outcome.AppliedByOther` → 마커 검증 후에만 `AlreadyApplied` 반환
- `autoExtend=true` 기본값 → 백엔드 미지원으로 제거
- 4단계 게이트 (precheck/lock/in-lock recheck/migration/post-skip) 도입
- `nodeId` → `lockOwner` 매핑

E3~E5 도 같은 절차 적용.

---

## L2: in-lock recheck — 멱등성 보장의 핵심

### 문제
"먼저 들어온 인스턴스가 마이그레이션, 나머지는 skip" 단순 모델은 leader 가 마이그레이션 완료 → lock 해제 → 다음 인스턴스가 lock 획득 시 다시 마이그레이션 시도 위험.

### 교훈
락 획득 후 마이그레이션 전 한 번 더 `isApplied()` 호출하여 idempotent 보장. 완료 후 lock 해제 → 후속 인스턴스가 lock 획득 → in-lock recheck → `AlreadyApplied` 반환 → migration skip.

```kotlin
elector.runIfLeader(lockName) {
    if (isApplied()) return@runIfLeader Outcome.AlreadyApplied(...)  // ← 핵심
    migration()
    Outcome.Migrated(...)
}
```

---

## L3: action 외부 try/catch — runIfLeader 의 lambda 예외 처리

### 문제
`runIfLeader { try migration() / catch Failed }` 처럼 action 안에서 catch 하면 ExposedJdbc backend 의 history 가 success 로 기록됨 (action 이 정상 종료한 것처럼 보임).

### 교훈
runIfLeader 호출 전체를 try/catch 로 감싸서 lambda 예외를 외부에서 잡고 `Outcome.Failed` 로 매핑.
runIfLeader 의 try-finally unlock 계약은 backend 가 보장 — 외부 catch 만 추가하면 안전.

```kotlin
val inLockOutcome: Outcome? = try {
    elector.runIfLeader(lockName) { ... }
} catch (e: CancellationException) { throw e }
catch (e: Exception) { return Outcome.Failed(...) }
```

---

## L4: sync API 에서도 `catch (Throwable)` → `catch (Exception)` 권장

### 문제
초기 코드는 `catch (Throwable)` 로 모든 예외를 `Outcome.Failed` 로 변환 → `Error` (OOM, StackOverflow), `InterruptedException` 까지 삼킴.

### 교훈
sync 함수에서도:
- `Throwable` → `Exception` 으로 좁혀 `Error` 누수 방지
- `kotlin.coroutines.cancellation.CancellationException` 명시적 재throw — coroutines 의존성 없이 stdlib 에서 제공

E1 BatchScheduler 도 같은 패턴 적용 검토 필요 (next round refactor).

---

## L5: Codex CLI 사용 — `codex exec` vs `codex review`

### 문제
- `codex exec --skip-git-repo-check "prompt"` → 임의 prompt 실행 (spec/plan 검토용)
- `codex review` → 코드 리뷰 전용. `--uncommitted` 와 prompt 동시 사용 불가.

### 교훈
- spec/plan 검토: `codex exec --skip-git-repo-check "검토 프롬프트"`
- 코드 변경 리뷰: `codex review --uncommitted` (no prompt) 또는 `codex review` + prompt
- 자동 review 가 출력 비어있을 시 spec/plan 단계 사전 리뷰로 대체 가능 (이번 케이스)

---

## L6: examples 모듈 paths-filter — backend 의존성 포함 필수

### 문제
초기 plan 은 `examples/migration-gate/**` 만 트리거. Codex 가 leader-core/leader-exposed-core/buildSrc/gradle 추가 권고.

### 교훈
examples 모듈 CI paths-filter 는:
- 자기 자신 (`examples/<name>/**`)
- 직접 의존 backend (`leader-<backend>/**`)
- 공통 인프라 (`leader-core/**`, 추가 의존 시 `leader-exposed-core/**` 등)

backend API 변경 시 examples 도 재테스트 필요 — paths-filter 누락 시 silent regression 위험.
