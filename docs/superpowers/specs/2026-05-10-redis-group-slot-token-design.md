# Spec — Redis Group minLeaseTime slot-token TTL 재설계

**Issue**: #151
**관련 PR (참조)**: #149 (core/local minLeaseTime), #150 (single lock backend TTL 위임)
**작성일**: 2026-05-10
**영향 모듈**: `leader-redis-lettuce/`, `leader-redis-redisson/`

---

## 1. 배경 / 문제

PR #150 머지로 single lock backend 들 (Lettuce/Redisson 단일 락, MongoDB, Hazelcast, Exposed JDBC/R2DBC) 의 `minLeaseTime` 은 backend TTL 위임 방식으로 통일됨. 그러나 Redis group elector (Lettuce + Redisson) 는 구조적 한계로 위임 불가능했음:

- **Lettuce**: `LettuceSemaphore` 가 단일 INTEGER 카운터 — slot 식별자 없음, TTL 없음
- **Redisson**: 표준 `RSemaphore` — clientId/slot 추적 안 됨

결과: group 의 `minLeaseTime` 시맨틱 구현 불가. 또한 클라이언트 비정상 종료 시 슬롯 영구 누수.

---

## 2. 목표

Redis group elector 모델을 **slot-token 기반**으로 재설계하여:

1. 각 acquired slot 이 **고유 token + expiry score** 를 가짐
2. 빠른 action 완료 시 token 의 score 만 갱신하여 **minLeaseTime 동안 slot 점유 유지** (caller-park 없이 backend TTL 위임)
3. 클라이언트 crash 시 **다음 acquire 시점에 자동 회수** (외부 reaper 불필요)
4. Lettuce / Redisson 양쪽 backend 의 동작 의미 통일
5. 기존 `maxLeaders`, `waitTime`, `leaseTime` semantic backward compatibility 유지

---

## 3. 채택 모델

### 3.1 Lettuce — Sorted Set + Lua atomic script

`lg:{lockName}` ZSET. score = expiryAt (epoch milliseconds). member = token (Base58.randomString(8)).

**ACQUIRE Lua** (server-side clock으로 클럭 skew 차단):
```lua
-- KEYS[1] = slotKey, ARGV[1] = maxLeaders, ARGV[2] = token, ARGV[3] = leaseTimeMs
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMs)
if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[1]) then
  redis.call('ZADD', KEYS[1], nowMs + tonumber(ARGV[3]), ARGV[2])
  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) + 5000)
  return ARGV[2]
end
return ''
```

**RELEASE Lua** (server-side clock):
```lua
-- KEYS[1] = slotKey, ARGV[1] = token, ARGV[2] = remainingMinLeaseMs
if tonumber(ARGV[2]) > 0 then
  local t = redis.call('TIME')
  local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
  return redis.call('ZADD', KEYS[1], 'XX', nowMs + tonumber(ARGV[2]), ARGV[1])
else
  return redis.call('ZREM', KEYS[1], ARGV[1])
end
```

**STATUS Lua** (server-side clock):
```lua
-- KEYS[1] = slotKey, ARGV[1] = maxLeaders
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, nowMs)
local active = redis.call('ZCARD', KEYS[1])
return { active, tonumber(ARGV[1]) - active }
```

**근거 (Codex P1)**: 클라이언트 clock skew 시 `nowMs` 클라이언트 사용은 fast client 가 valid slot 제거 / slow client 가 overlong lease 생성 가능. **모든 시간 비교는 Redis 서버 시간 기준** (`redis.call('TIME')`).

### 3.2 Redisson — RPermitExpirableSemaphore

`lg:{lockName}` RPermitExpirableSemaphore. Redisson 표준 API (Redisson 4.3.1):
- `trySetPermits(maxLeaders): Boolean` — **첫 사용 전 반드시 호출** (멱등). 안 하면 0 permits 로 시작하여 acquire 영구 실패.
- `tryAcquire(waitTime, leaseTime, unit): String?` — permitId 반환 (null = 획득 실패)
- `updateLeaseTime(permitId, leaseTime, unit): Unit` (sync) / `updateLeaseTimeAsync(permitId, leaseTime, unit): RFuture<Void>` (async) — minLease 위임
  - 주의: `tryUpdateLeaseTime` 은 Redisson 4.3.1 에 없음. `updateLeaseTime` 만 존재.
- `release(permitId)` — 즉시 해제

자체 Lua script 불필요. Redisson watchdog 자연 결합.

**근거 (Codex P1+P2)**:
- 새 `lg:{lockName}` 키에 `trySetPermits(maxLeaders)` 멱등 초기화 누락 시 모든 acquire 실패 → group election 영구 안 됨
- API 명칭 정확: `updateLeaseTime` / `updateLeaseTimeAsync` (Redisson 4.3.1)

### 3.3 키 prefix `lg:{lockName}` 도입 (backward compat)

기존 `lockName` (string counter / RSemaphore Hash) 와 충돌 회피 — 롤링 배포 중 구버전 클라이언트와 새 버전 클라이언트가 다른 키 사용 → WRONGTYPE 에러 방지.

**Trade-off**: 롤링 배포 중 일시적으로 maxLeaders 의 효과가 2배가 될 수 있음 (구/신 그룹 분리). 완화책: 구버전 pod drain 후 신버전 배포.

---

## 4. 인터페이스 변경

`LeaderGroupElector` 인터페이스 자체 변경 없음. Token 은 내부 구현 디테일 — caller 에 노출하지 않음.

`LeaderGroupElectionOptions` 변경 없음 (`minLeaseTime` 이미 존재).

---

## 5. 동작 / 계약

### 5.1 acquire

```
client → elector.runIfLeader(lockName, action)
  startedAtNanos = System.nanoTime()
  token = backend.tryAcquire(lockName, waitTime, leaseTime)
  token == null → return null   (ShedLock skip-on-contention)
  try { result = action() }
  finally {
    remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
    backend.release(lockName, token, remainingMs)   // remainingMs > 0 → score 갱신, else → 즉시 해제
  }
  return result
```

### 5.2 minLeaseTime 시맨틱

- `minLeaseTime == 0` (default): action 종료 즉시 slot 해제 (기존 동작 동일)
- `minLeaseTime > 0`: action 이 minLeaseTime 보다 빨리 끝나도 slot 은 minLeaseTime 만료 까지 유지 (다른 client 가 같은 slot 못 잡음)
- `minLeaseTime > leaseTime`: `IllegalArgumentException` (기존 init 검증, 변경 없음)
- `minLeaseTime` 만료 후 자동으로 다음 client 가 acquire 가능

### 5.3 leak recovery

클라이언트 crash 시:
- Lettuce: 다음 acquire 시 `ZREMRANGEBYSCORE 0 nowMs` 가 만료 entry 자동 정리
- Redisson: `RPermitExpirableSemaphore` 가 backend TTL 로 permit 자동 회수

외부 reaper 불필요. `leaseTime` 만료 후 다음 acquire 가 success.

### 5.4 split-brain 방지

`leaseTime` 이 action 실제 시간보다 짧으면:
- backend 가 slot 자동 만료 → 다른 client acquire → 동시 실행 (split-brain)
- watchdog auto-extension 미구현 (이번 issue scope 외)
- caller 책임: `leaseTime > worst-case action duration` 보장

---

## 6. DoD

### Lettuce

- [ ] `LettuceSlotTokenGroup` 신규 — ZSET + 3 Lua scripts (ACQUIRE/RELEASE/STATUS)
- [ ] sync/async/suspend API 모두 (tryAcquire / release / availableSlots / activeCount)
- [ ] `LettuceLeaderGroupElector` — `LettuceSemaphore` → `LettuceSlotTokenGroup` 교체 + minLeaseTime release
- [ ] `LettuceSuspendLeaderGroupElector` — 동일 + CancellationException 재throw 패턴 적용
- [ ] `LettuceSemaphore` / `LettuceSuspendSemaphore` `@Deprecated`
- [ ] `LettuceSlotTokenGroupTest` 신규 — primitive 단위 테스트
- [ ] `LettuceLeaderGroupElectionTest` + `LettuceSuspendLeaderGroupElectorTest` minLeaseTime 시나리오 + crash recovery 추가

### Redisson

- [ ] `RedissonLeaderGroupElector` — `RSemaphore` → `RPermitExpirableSemaphore`, minLeaseTime → `updateLeaseTime` (sync) / `updateLeaseTimeAsync` (async)
- [ ] `RedissonSuspendLeaderGroupElector` — 동일 + CancellationException 재throw 적용
- [ ] `RedissonLeaderGroupElectionTest` + `RedissonSuspendLeaderGroupElectorTest` minLeaseTime 시나리오 + crash recovery

### 공통

- [ ] 기존 group 테스트 회귀 0
- [ ] `./gradlew :leader-redis-lettuce:test :leader-redis-redisson:test` 모두 통과
- [ ] `./gradlew detekt` 새 violation 0
- [ ] README.md / README.ko.md 양 모듈 동기화 (slot-token 모델 + crash recovery 명시)
- [ ] CLAUDE.md 갱신 (group 백엔드 모델 변경 반영)
- [ ] `docs/lessons/2026-05-10-redis-group-slot-token.md` lesson doc

---

## 7. 테스트 시나리오 (필수)

각 backend × blocking/suspend 4종 모두에 적용:

1. **빠른 action + minLeaseTime > runtime**: caller 즉시 반환 확인 + 다른 client 가 minLeaseTime 만료 전 acquire 실패 확인
2. **minLeaseTime 만료 후**: 다음 client acquire 성공
3. **`minLeaseTime == 0`**: 즉시 release 회귀 방지
4. **maxLeaders 동시 점유 + 모두 minLeaseTime 보유**: maxLeaders+1 번째 client 는 waitTime 내 실패
5. **slot 누수 회수**: client crash 시뮬레이션 (release 호출 없음) → leaseTime 만료 후 다른 client acquire 성공
6. **Suspend 코루틴 취소 + minLeaseTime > 0**: NonCancellable release 가 minLease score 갱신 정확히 적용
7. **autoExtend + minLeaseTime > 0** (group autoExtend 도입 시): IllegalArgumentException — 이번 PR scope 외, group autoExtend 미구현이므로 N/A

---

## 8. 위험 / 완화

| 위험 | 완화 |
|------|------|
| 키 자료구조 변경 → 롤링 배포 중 WRONGTYPE | `lg:{lockName}` prefix 도입 |
| maxLeaders 일시 2배 (구/신 분리) | 구 pod drain 후 신 배포 명시 (CHANGELOG) |
| Lua script 에러 → 락 누수 | release try-catch + 어차피 leaseTime TTL 로 자동 회수 |
| `updateLeaseTime` race (minLease 가 release 시점 직전 만료) | RFuture<Boolean> false 반환 시 warn log 만, 던지지 않음 — 이미 자동 만료된 정상 결과 |
| backward compat (행동 변경) | `minLeaseTime` semantic 자체는 동일 — caller-park → backend-TTL, 외부 관찰 동일 |
