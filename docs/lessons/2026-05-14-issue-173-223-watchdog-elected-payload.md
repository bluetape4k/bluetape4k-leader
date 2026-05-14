# 레슨: #173 watchdog 정상 종료 + #223 Elected 페이로드 (2026-05-14)

## 배경

PR #232 (#223), PR #233 (#173)로 두 가지 작업을 병렬로 진행했다.

- **#223**: `LeaderElectionEvent.Elected`에 `leaderId`·`leaseExpiry` 필드 추가 (LeaderStateFlow epic 선행 조건)
- **#173**: `LeaderLeaseAutoExtender` watchdog에 정상 종료 및 `RejectedExecutionException` 보호 추가

---

## 주요 교훈

### 1. 코드 리뷰 없이 PR 먼저 생성 — 절차 위반

PR을 생성한 직후 사용자가 "Codex 리뷰 요청했나?"라고 지적. CLAUDE.md의 `Before Creating A PR (MANDATORY)` 체크리스트와 메모리 `feedback_pr_code_review.md` 모두 **PR 생성 전 코드 리뷰 필수**를 명시한다. 구현 완료 → 리뷰 → fix → PR 순서를 반드시 지켜야 한다.

### 2. CRITICAL: `scheduleWithFixedDelay` 에서의 REE 전파

초기 구현은 tick 람다 내부에서만 `RejectedExecutionException`을 잡았다. 리뷰에서 발견된 CRITICAL 문제: 코드 경로에 `scheduler.scheduleWithFixedDelay()` 호출 자체가 REE를 던질 수 있고, 이는 `runIfLeader` 밖으로 전파되어 "절대 throw 하지 않음" 계약을 깨뜨린다.

**해결책**: `AtomicReference<ScheduledFuture<*>?>`를 사용해 `lateinit var`를 제거하고, `scheduleWithFixedDelay` 호출 전체를 try/catch(REE)로 감싸 `NoopCloseable` 반환.

```kotlin
val futureRef = AtomicReference<ScheduledFuture<*>?>(null)
val future = try {
    scheduler.scheduleWithFixedDelay({ … futureRef.get()?.cancel(false) … }, …)
} catch (ex: RejectedExecutionException) {
    return NoopCloseable  // runIfLeader never throws 계약 유지
}
futureRef.set(future)
```

### 3. HIGH: `DisposableBean`만으로는 JVM-scoped object 복구 불충분

`LeaderLeaseAutoExtender`는 Kotlin `object` — JVM classloader 범위. Spring context가 닫히면 `destroy()` → `shutdown()`으로 scheduler가 SHUTDOWN 상태가 된다. 같은 JVM에서 다음 컨텍스트가 시작될 때(`@DirtiesContext` 테스트, 순차적 컨텍스트 재시작) lifecycle bean은 재생성되지만 `restart()`를 호출하지 않으면 scheduler가 영구적으로 SHUTDOWN 상태로 남는다.

**해결책**: `InitializingBean` 대칭 구현.

```kotlin
class LeaderLeaseAutoExtenderLifecycle : InitializingBean, DisposableBean {
    override fun afterPropertiesSet() = LeaderLeaseAutoExtender.restart()  // idempotent
    override fun destroy() = LeaderLeaseAutoExtender.shutdown()
}
```

### 4. `AtomicReference` 패턴 — tick 내부 future 자기 취소

`lateinit var future`는 tick 람다가 future를 자기 참조로 취소하는 데 사용했다. 이 패턴은 `scheduleWithFixedDelay`를 try/catch로 감싸면 컴파일러가 "미초기화 사용" 오류를 낼 수 있다. `AtomicReference`로 교체하면:
- try/catch 래핑 가능
- tick 내부에서 `futureRef.get()?.cancel(false)` null-safe 접근
- `futureRef.set(future)` 는 첫 tick 이전(초기 지연 cadence ≥ 25ms)에 반드시 완료

### 5. 7-Tier 리뷰에서 발견된 MEDIUM: 핵심 fix 미테스트

CRITICAL fix(REE 보호)가 실제로 동작함을 증명하는 테스트가 없었다. 7-Tier 리뷰에서 지적 후 추가:

```kotlin
@Test
fun `start on shutdown scheduler returns NoopCloseable without throwing`() {
    LeaderLeaseAutoExtender.shutdown()
    val result = runCatching { LeaderLeaseAutoExtender.start(true, 90.milliseconds) { true } }
    result.isSuccess.shouldBeTrue()
}
```

`afterPropertiesSet()` 경로도 미테스트였음 — 마찬가지로 추가.

### 6. 7-Tier 리뷰 시점 — PR 머지 전

이 세션에서 7-Tier 리뷰를 PR 생성 후에 수행했다. 이상적 순서: 구현 → (로컬 검토) → 7-Tier 리뷰 → fix → PR 생성. 리뷰에서 CRITICAL/HIGH를 발견했을 때 이미 PR이 열린 상태였으므로 force-push가 필요했다.

### 7. PR 머지 순서와 CHANGELOG 충돌

#223과 #173을 별도 PR로 병렬 진행. #232 먼저 머지 후 #233 머지 시도 → CHANGELOG 충돌. 같은 `[Unreleased]` 섹션을 양쪽이 수정했기 때문. 해결: #233 브랜치를 `origin/develop`으로 rebase 후 충돌 수동 해결.

---

## 체크리스트 추가 항목

- [ ] `object` 싱글턴에 shutdown/restart 추가 시 항상 `InitializingBean`도 대칭 구현
- [ ] `scheduleWithFixedDelay` 호출 시 REE를 호출 지점에서 캐치 (tick 내부만으로는 부족)
- [ ] CRITICAL fix는 반드시 해당 fix 경로를 직접 커버하는 테스트 작성
- [ ] 병렬 PR이 같은 CHANGELOG 섹션을 수정하면 나중 PR이 rebase 필요 — 병합 순서를 미리 계획
