# Lessons Learned — E3 Webhook Poller (2026-05-10)

**관련 PR**: TBD
**관련 Issue**: #156 (Epic #36)
**영향 모듈**: `examples/webhook-poller/`, `.github/workflows/{ci,nightly}.yml`, `settings.gradle.kts`

## L1: claim/ack 모델 — at-least-once + idempotent handler

### 문제
초기 spec 은 단순 fetch + handler 실행 — leader cancel/crash/lease 만료 시 event loss/duplicate 발생.

### 교훈
PENDING/CLAIMED/DONE/FAILED 4-state model + atomic `findOneAndUpdate` claim. Claim 만료 시 reclaim 가능. handler 는 **idempotent** 작성 필수.
- Claim filter: `{ status: PENDING } OR { status: CLAIMED, claimExpiresAt < now }` + `attempts < maxAttempts`
- handler 성공 → DONE
- handler 실패 + attempts < maxAttempts → PENDING 으로 되돌림 (재시도)
- handler 실패 + attempts >= maxAttempts → FAILED (DLQ 대체)

E5 (tenant-aggregator) 도 유사 패턴 적용 권장.

---

## L2: claim ownership 가드 — stale owner 의 update 차단

### 문제
원본 owner 가 handler 실행 중 claim 만료 → 다른 인스턴스가 reclaim → 원본 owner 가 깨어나서 `eventId` 만으로 update → 새 owner 의 CLAIMED 를 덮어씀.

### 교훈
`updateOne` filter 에 `eventId` + `status = CLAIMED` + `claimedBy = nodeId` 동시 검증.

```kotlin
Filters.and(
    Filters.eq(FIELD_EVENT_ID, event.eventId),
    Filters.eq(FIELD_STATUS, CLAIMED.name),
    Filters.eq(FIELD_CLAIMED_BY, options.nodeId),
)
```

`matchedCount=0` 시 "claim ownership lost" log warn — silent skip 으로 race 무해화.

분산 시스템에서 lease 기반 모든 작업에 동일 패턴 적용 (E5 LeaderGroup).

---

## L3: maxAttempts 의 강제 — claim filter 자체에 검증

### 문제
초기 구현 은 markFailureOrRequeue 에서만 maxAttempts 검증. 그러나 `attempts >= maxAttempts` 인 expired CLAIMED 는 여전히 claim filter 에 매칭됨 → 다음 인스턴스가 reclaim → handler 또 호출 → maxAttempts 의미 없음.

### 교훈
claim filter 자체에 `attempts < maxAttempts` 조건 추가 — DB 레벨에서 maxAttempts 강제. 별도 sweeper job 불필요.

---

## L4: Options 와 외부 주입 의존성의 desync 방지

### 문제
`WebhookPollerOptions` 에 `leaseTime`, `waitTime` 설정해도 외부에서 주입된 `SuspendLeaderElector` 는 자체 ctor 옵션 사용 → user 가 설정한 옵션이 무시됨.

### 교훈
외부 주입 의존성의 옵션은 Options 에서 제거. caller 가 elector 생성 시 직접 옵션 전달:

```kotlin
// 권장
val elector = MongoSuspendLeaderElector(lockColl, MongoLeaderElectionOptions(
    leaderOptions = LeaderElectionOptions(waitTime = ..., leaseTime = ...)
))
val poller = WebhookPoller(elector, eventColl, WebhookPollerOptions(
    nodeId, lockName, pollInterval, batchSize, maxAttempts, claimDuration  // leaseTime/waitTime 제거
), handler)
```

또는 Options 가 elector 까지 책임진다면 elector factory + options 통합. 양쪽 다 명시적이어야 desync 방지.

---

## L5: suspend API + JUnit 5 — Unit 명시 필수

### 문제
`runBlocking { ... }` 마지막 식이 non-Unit 이면 JUnit 5 가 테스트를 silently skip — 통과/실패 카운트 모두 변동 없음.

### 교훈
suspend 테스트 함수 시그니처에 명시적 `: Unit`:

```kotlin
@Test
fun `시나리오 검증`(): Unit = runBlocking { ... }   // ✅ Unit 명시
// 또는
@Test
fun `시나리오 검증`() = runTest { ... }              // ✅ runTest 는 Unit 자동 추론
```

E5 SuspendLeaderGroup 테스트 작성 시 동일 주의.

---

## L6: Codex review P2 vs P3 정확도

### 사례
E3 에서 Codex review 가 BLOCKER 2 + HIGH 5 + MEDIUM/LOW 다수 발견. spec 단계 반영 후 코드 review 재실행 시 추가 P2 3건 (옵션 desync, claim ownership 가드, maxAttempts 강제) 발견.

### 교훈
Codex review 는 **spec/plan/code 모든 단계** 에서 의뢰 가치 있음. 같은 이슈도 단계별로 다른 각도로 발견됨:
- spec 단계: 누락 시나리오, API 모양
- plan 단계: 의존 순서, over-engineering
- code 단계: race condition, 구현 디테일

E4, E5 도 3 단계 모두 수행.
