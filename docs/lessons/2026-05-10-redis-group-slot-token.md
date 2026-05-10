# Lessons Learned — Redis Group slot-token TTL 재설계 (2026-05-10)

**관련 PR**: TBD
**관련 Issue**: #151
**영향 모듈**: `leader-redis-lettuce/`, `leader-redis-redisson/`

## L1: Lua server-side TIME — 클라이언트 clock skew 차단

### 문제
초기 spec 은 ACQUIRE Lua 가 client `nowMs` 인자 사용. Codex P1 — 클라이언트 clock skew 시 fast client 가 valid slot 제거 / slow client 가 overlong lease 생성 가능 → maxLeaders 위반.

### 교훈
모든 시간 비교는 Redis 서버 시간 기준 (`redis.call('TIME')`):

```lua
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
```

ACQUIRE/RELEASE/STATUS 모든 script 에 적용. score (expiryAt) 도 server-side time 으로 계산.

다른 distributed lock 에서도 multi-client 비교는 server-side time 기본.

---

## L2: RPermitExpirableSemaphore — `trySetPermits(maxLeaders)` 멱등 호출 필수

### 문제
새 `lg:{lockName}` 키에 `getPermitExpirableSemaphore(name)` 만 호출 시 0 permits 로 시작 → 모든 `tryAcquire` null 반환 → group election 영구 실패.

### 교훈
RPermitExpirableSemaphore 는 RSemaphore 와 달리 자동 max permits 초기화 없음. 사용 전 반드시:

```kotlin
val semaphore = redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")
semaphore.trySetPermits(maxLeaders)  // 멱등, 이미 설정됐으면 no-op
```

Suspend 버전도 `trySetPermitsAsync(maxLeaders).await()` 로 동등 처리.

---

## L3: Redisson 4.3.1 API — `updateLeaseTime` (not `tryUpdateLeaseTime`)

### 문제
spec 초안에 `tryUpdateLeaseTime` 사용 — Redisson 4.3.1 에 미존재. 컴파일 에러.

### 교훈
Redisson 4.3.1 `RPermitExpirableSemaphore`:
- **sync**: `updateLeaseTime(permitId, leaseTime, unit): Unit`
- **async**: `updateLeaseTimeAsync(permitId, leaseTime, unit): RFuture<Boolean>`

`tryUpdateLeaseTime` 는 4.x 어느 버전에서도 본 적 없음 — 항상 `updateLeaseTime` 으로 시작.

다른 lib API 사용 시 spec 작성 단계에서 정확한 시그니처 검증 필수 (예: `gh search` 로 actual 코드 확인).

---

## L4: `startedAtNanos` 캡처 — acquire 성공 후

### 문제
spec 초안: `startedAtNanos = System.nanoTime()` 을 `tryAcquire` 호출 전 캡처. waitTime 이 non-zero 이고 caller 가 대기한 후 acquire 성공하면, waitTime 이 minLeaseTime 에서 차감 → fast action 후 release 시 minLease 가 음수로 되어 즉시 release.

### 교훈
acquire 가 성공한 후, action 실행 직전에 캡처:

```kotlin
val token = slotGroup.tryAcquire(waitTime) ?: return null
val startedAtNanos = System.nanoTime()  // ⭐ 여기
try { return action() } finally {
    val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
    slotGroup.release(token, remainingMs)
}
```

기존 backend-TTL 구현 (Lettuce single lock, Redisson single lock 등) 도 동일 패턴.

---

## L5: ZADD XX 가드 — expired token 부활 방지

### 문제
초기 RELEASE_SCRIPT: `if remainingMs > 0 then ZADD XX score=(now+remaining) token`. caller 가 release 호출 시점에 token 이 이미 만료되어 ZREM 됐다면 XX 는 no-op (좋음). 하지만 ZSET 에 잔존하지만 score 가 이미 nowMs 이하 (= expired but not yet purged) 면 XX 가 silent 하게 lease 연장 → caller 가 잃은 slot 의 lease 부적절 연장.

### 교훈
RELEASE_SCRIPT 에서 `ZSCORE` 로 현재 score 확인 후 nowMs 보다 큰 경우만 갱신:

```lua
local cur = redis.call('ZSCORE', KEYS[1], ARGV[1])
if cur and tonumber(cur) > nowMs then
  return redis.call('ZADD', KEYS[1], 'XX', nowMs + tonumber(ARGV[2]), ARGV[1])
end
return 0
```

distributed TTL extend 시 항상 "여전히 살아있는지" 확인 후 연장 (CAS 패턴).

---

## L6: tryAcquireAsync — backend error 보존 + retry

### 문제
async retry handler 가 backend error (Redis connection/auth/script) 를 `null` 로 변환 → contention 과 구분 불가. Redis outage 시 silent skip — sync/suspend path 와 불일치.

### 교훈
Async retry 에서 backend error 는 보존, deadline 도달 시 last error 로 future fail:

```kotlin
val lastError = AtomicReference<Throwable?>(null)
fun attempt(): CompletableFuture<String?> {
    return RedisScriptRunner.runAsync(...).handle { result, error ->
        if (error != null) { lastError.set(error); null }
        else result
    }.thenCompose { result ->
        when {
            !result.isNullOrEmpty() -> CompletableFuture.completedFuture(result)
            System.nanoTime() < deadlineNanos -> CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
            lastError.get() != null -> CompletableFuture.failedFuture(lastError.get()!!)
            else -> CompletableFuture.completedFuture(null)
        }
    }
}
```

contention 만 `null` 로 노출. 단순 `null` 변환은 핵심 계약 (acquire 실패 = lock 없음) 위반.

---

## L7: runAsyncIfLeader — release 완료 후 outer future complete

### 문제
`actionFuture.handle { ... releaseAsync().whenComplete { ... } ...; if (error) throw else value }` — release 는 fire-and-forget. outer future 가 release 완료 전에 complete → caller 가 chained 호출 시 slot 이 still occupied 로 보임.

### 교훈
`thenCompose` 로 release future 까지 await:

```kotlin
actionFuture.handle<Pair<T?, Throwable?>> { v, e -> Pair(v, e) }
    .thenCompose { (value, error) ->
        slotGroup.releaseAsync(token, remainingMs)
            .exceptionally { releaseError -> log.warn(releaseError) { "..." }; Unit }
            .thenApply { if (error != null) throw error else value }
    }
```

async semaphore/lock 의 release 는 항상 outer future 에 chain — sequential ordering 보장.

---

## L8: untracked 파일 staging 검증 — agent 결과 수정 시 매번

### 문제
agent 가 신규 파일 (LettuceSlotTokenGroup.kt + Test) 생성 후 git add 안 함 → `git status` untracked. 테스트는 working tree 기준 통과, 그러나 commit 후 PR 은 컴파일 실패.

### 교훈
agent delegate 후 항상:
- `git status -uall` 로 untracked 파일 검증
- `git add -A` 로 일괄 staging
- 실수로 commit 안 한 신규 파일이 PR 에 누락되지 않도록

E4 lessons L4 ("agent 위임 후 settings.gradle.kts/CI workflow 누락") 와 동일 교훈 — 위임 후 검증 단계 필수.
