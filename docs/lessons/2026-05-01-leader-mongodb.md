# Lessons Learned — leader-mongodb (2026-05-01)

**관련 PR**: #46
**영향 모듈**: `leader-mongodb`

---

## L1: MongoDB Kotlin Coroutine Driver v5.x — `FindFlow`에 `first()` 없음

### 문제
`MongoSuspendLock.isHeldByCurrentInstance()`에서 `collection.find(...).first()`를 호출했더니
`Unresolved reference 'first'` 컴파일 에러 발생.
MongoDB Kotlin 코루틴 드라이버 v5.x는 `FindFlow`(Flow 기반)를 반환하며,
`CoroutineFindIterable`의 `.first()` 확장함수가 존재하지 않는다.

### 교훈
MongoDB Kotlin 코루틴 드라이버에서 단건 존재 여부 확인은 `countDocuments()` 사용.
```kotlin
// ❌ 컴파일 에러
collection.find(filter).first()

// ✅ 올바른 패턴
collection.countDocuments(filter) > 0
```

---

## L2: `runTest` + 실제 IO — virtual time과 real time 충돌

### 문제
`withContext(NonCancellable)` 안에서 `withTimeout(options.releaseTimeout)`을 사용했더니,
`runTest`의 virtual time이 실제 MongoDB IO보다 먼저 타임아웃을 발동시켜
`lock.unlock()` 호출이 완료되기 전에 코루틴이 취소됨.
결과: 락 문서가 삭제되지 않아 두 번째 `runIfLeader` 호출이 null 반환.

### 교훈
- MongoDB/Testcontainers 등 실제 IO가 있는 suspend 테스트는 `runTest` 대신 `runSuspendIO` 사용.
- `runSuspendIO` = `runBlocking(Dispatchers.IO)` — 실제 시간, 실제 디스패처.
- cleanup 경로(`NonCancellable` 블록)에 `withTimeout`은 안전하지 않음 — 제거하고 드라이버 자체 타임아웃에 위임.

---

## L3: `CancellationException` — `catch(Exception)` 블록에서 반드시 재throw

### 문제
`withContext(NonCancellable)` 안이라도 `catch(Exception)`은 `CancellationException`을 잡아버린다.
(NonCancellable은 새 코루틴의 취소를 막을 뿐, 예외 계층을 바꾸지 않는다)

### 교훈
`catch(Exception)` 앞에 항상 `catch(CancellationException) { throw e }`를 추가:
```kotlin
// ✅ 올바른 패턴
try { doSomething() }
catch (e: CancellationException) { throw e }   // 반드시 재throw
catch (e: Exception) { log.warn(e) { "실패" } }
```
CLAUDE.md 규칙: "never catch `CancellationException` without rethrowing"

---

## L4: `isHeldByCurrentInstance()` before `unlock()` — 불필요한 DB 왕복

### 문제
`unlock()` 호출 전에 `isHeldByCurrentInstance()`로 소유 여부를 확인하는 패턴을 사용했는데,
이는 토큰 기반 락에서 불필요한 DB 왕복을 추가할 뿐이다.
`deleteOne(_id=lockKey, token=token)`은 토큰 불일치 시 `deletedCount=0`을 반환하므로 이미 안전.

### 교훈
토큰 기반 분산 락에서 `unlock()`은 `deleteOne(key + token)` 하나로 충분.
pre-check은 race condition도 막지 못하고 성능만 저하시킴 — 제거.

---

## L5: `validateLockName` — 4개 파일 중복 → `internal fun`으로 추출

### 문제
`MongoLock`, `MongoSuspendLock`, `MongoLeaderElection`, `MongoSuspendLeaderElection`,
`MongoLeaderGroupElection`, `MongoSuspendLeaderGroupElection` 각자에
동일한 `private fun validateLockName(lockName: String)` 구현이 있었음.

### 교훈
`lock` 패키지의 `MongoLock.kt` 하단에 `internal fun validateLockName`으로 추출.
동일 모듈 내 모든 파일에서 import 없이 접근 가능.
DRY 원칙: 3회 이상 중복이면 반드시 추출.

---

## L6: `MongoSuspendLeaderGroupElection` 이중 컬렉션 — namespace 동등성 검증

### 문제
state 조회용 sync `MongoCollection`과 락 획득/해제용 coroutine `MongoCollection`이 동일한
컬렉션을 가리켜야 한다는 계약을 런타임에 검증하지 않았음.
잘못된 컬렉션을 전달해도 silent하게 다른 컬렉션에 쓰는 버그 가능.

### 교훈
`init` 블록에서 `namespace.fullName` 동등성을 즉시 검증:
```kotlin
init {
    require(groupCollection.namespace.fullName == coroutineGroupCollection.namespace.fullName) {
        "groupCollection과 coroutineGroupCollection은 동일한 namespace여야 합니다"
    }
}
```

---

## L7: `MongoLeaderGroupElectionTest - state` 타이밍 — `perSlotWait` 계산 주의

### 문제
`maxLeaders=3, waitTime=30s`로 설정한 테스트에서 `perSlotWait = 30/3 = 10s`가 되어
`acquiredLatch.await(5, TimeUnit.SECONDS)`가 슬롯 획득 전에 타임아웃.

### 교훈
그룹 슬롯 경합 테스트는 `fastOptions`(waitTime을 짧게)로 별도 옵션을 만들고,
`await` 타임아웃은 `perSlotWait * maxLeaders * 여유계수` 이상으로 설정.
```kotlin
val fastOptions = MongoLeaderGroupElectionOptions(
    LeaderGroupElectionOptions(maxLeaders = 3, waitTime = Duration.ofSeconds(5), ...)
)
acquiredLatch.await(10, TimeUnit.SECONDS)  // perSlotWait(5/3≈1.7s) * 3 + 여유
```
