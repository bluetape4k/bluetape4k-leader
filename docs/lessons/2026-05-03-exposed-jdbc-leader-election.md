# Lessons Learned — leader-exposed-jdbc 구현 (2026-05-03)

**관련 이슈**: #21
**영향 모듈**: `leader-exposed-jdbc`, `leader-exposed-core`, `leader-core`

---

## L1: Exposed DSL 람다 implicit receiver와 클래스 프로퍼티 이름 충돌

### 문제

`LeaderLockTable.update {}`, `insert {}`, `deleteWhere {}` 람다 내부에서 `lockOwner`, `lockName`, `token` 등 클래스 프로퍼티를 사용하면, Kotlin implicit receiver 우선순위에 의해 테이블 컬럼으로 해석된다.

예:
```kotlin
LeaderLockTable.insert {
    it[lockOwner] = lockOwner  // lockOwner = LeaderLockTable.lockOwner (컬럼)
}
// → VALUES (..., table.column, ...) — H2/PSQL이 거부, MySQL은 NULL로 허용
```

H2/PostgreSQL에서는 INSERT가 예외로 실패하고 `runCatching.onFailure { return@transaction false }`로 흡수되어 항상 false 반환. MySQL은 이 SQL을 NULL로 허용하여 통과.

### 교훈

Exposed 람다(`update {}`, `insert {}`, `deleteWhere {}`) 진입 **직전**에 클래스 프로퍼티를 로컬 변수로 반드시 추출.

```kotlin
private fun tryAcquireOnce(leaseTime: Duration): Boolean {
    val lockNameVal = this@ExposedJdbcLock.lockName   // 람다 진입 전 추출
    val tokenVal = this@ExposedJdbcLock.token
    
    return transaction(db) {
        LeaderLockTable.insert {
            it[LeaderLockTable.lockName] = lockNameVal  // 로컬 변수 사용
            it[LeaderLockTable.token] = tokenVal
        }
    }
}
```

DB별 증상 차이(H2/PSQL 실패 vs MySQL 통과)로 인해 DB 방언 버그로 오인할 수 있다.

---

## L2: `COUNT(*) > 0` 대신 `.empty().not()` 사용

### 문제

`selectAll().where { ... }.count() > 0` 패턴은 전체 카운트 쿼리를 실행하여 불필요한 오버헤드가 발생한다.

### 교훈

Exposed에서 존재 여부 확인은 `.empty().not()`을 사용한다:

```kotlin
// Before
LeaderLockTable.selectAll().where { ... }.count() > 0

// After
!LeaderLockTable.selectAll().where { ... }.empty()
```

내부적으로 `SELECT ... LIMIT 1` 또는 EXISTS 쿼리로 최적화된다.

---

## L3: tryLock에서 DB 오류 시 `return false` vs `false` 반환

### 문제

원본 코드의 `getOrElse { return false }` 패턴은 일시적 DB 오류(connection timeout, deadlock) 발생 시 남은 waitTime 예산을 버리고 즉시 실패한다.

### 교훈

DB 오류는 일시적일 수 있으므로, `false`로 반환하여 재시도 루프를 계속 진행:

```kotlin
}.getOrElse { e ->
    log.warn(e) { "DB 오류 (재시도 유지): lockName=$lockName, attempt=$attempt" }
    false  // return false가 아닌 false — 재시도 루프 계속
}
```

---

## L4: leader-core `validateLockName` 2-tier 검증 패턴

### 문제

`leader-exposed-jdbc`의 `validateExposedLockName`이 `leader-core`의 `validateLockName`을 호출하지 않고 blank/length만 검증하고 있었다. 주석도 "미존재로 직접 구현"이라고 잘못 기재.

### 교훈

각 백엔드 모듈의 검증 함수는 반드시 `leader-core`의 공통 함수를 먼저 호출해야 한다:

```kotlin
internal fun validateExposedLockName(lockName: String) {
    validateLockName(lockName)  // leader-core 공통 규칙 (허용 문자, 첫 글자, 255자)
    // Exposed 고유 추가 규칙이 있으면 여기에
}
```

새 백엔드 모듈 추가 시 반드시 `validateLockName` 호출 확인.

---

## L5: `recordCompleted`/`recordFailed` 중복 패턴 → `recordFinished` 추출

### 문제

이력 기록 메서드가 status만 다르고 나머지 로직이 동일하여 코드 중복이 발생.

### 교훈

`HistoryStatus` enum을 파라미터로 받는 `recordFinished`를 추출:

```kotlin
private fun recordCompleted(historyId: Long?, token: String, startedAt: Instant) =
    recordFinished(historyId, token, startedAt, HistoryStatus.COMPLETED)

private fun recordFailed(historyId: Long?, token: String, startedAt: Instant) =
    recordFinished(historyId, token, startedAt, HistoryStatus.FAILED)

private fun recordFinished(historyId: Long?, token: String, startedAt: Instant, status: HistoryStatus) { ... }
```

이 패턴은 `ExposedJdbcLeaderElection`과 `ExposedJdbcLeaderGroupElection` 양쪽에 동일하게 적용.
