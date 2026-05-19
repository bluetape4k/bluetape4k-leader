# leader-exposed-r2dbc

[English](README.md)

[Exposed R2DBC](https://github.com/JetBrains/Exposed)를 사용한 관계형 데이터베이스 기반 코루틴-네이티브 리더 선출 라이브러리.

---

## 개요

`leader-exposed-r2dbc`는 Exposed의 R2DBC 지원을 사용하여 `leader-core` 인터페이스를 구현합니다. 모든 데이터베이스 작업은 완전히 비블로킹(`suspendTransaction`)이므로, 요청별 전용 커넥션 스레드 풀 없이 코루틴 기반 서비스에 적합합니다.

락 전략: 단일 트랜잭션 내 `UPDATE WHERE lockedUntil < NOW()` + `INSERT IGNORE` — Redis나 외부 브로커가 필요 없습니다. H2(인메모리), PostgreSQL, MySQL 8을 지원합니다.

## 아키텍처

![Architecture diagram](../docs/images/readme-diagrams/leader-exposed-r2dbc-class-01.png)

## 구현체

| 클래스 | 인터페이스 | 설명 |
|--------|-----------|------|
| `ExposedR2dbcSuspendLeaderElector` | `SuspendLeaderElector` | `ExposedR2dbcLock` 기반 코루틴 단일 리더 |
| `ExposedR2dbcSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | `ExposedR2dbcGroupLock` 기반 코루틴 복수 리더 |
| `ExposedR2DbcSuspendLeaderElectorFactory` | `SuspendLeaderElectorFactory` | 팩토리: 호출마다 `ExposedR2dbcSuspendLeaderElector` 생성 |
| `ExposedR2DbcSuspendLeaderGroupElectorFactory` | `SuspendLeaderGroupElectorFactory` | 팩토리: 호출마다 `ExposedR2dbcSuspendLeaderGroupElector` 생성 |

## 사용법

### 데이터베이스 연결

```kotlin
// H2 인메모리 (개발/테스트)
val db = R2dbcDatabase.connect("r2dbc:h2:mem:///leader;MODE=MySQL;DB_CLOSE_DELAY=-1")

// PostgreSQL
val db = R2dbcDatabase.connect("r2dbc:postgresql://user:pass@localhost:5432/mydb")

// MySQL 8
val db = R2dbcDatabase.connect("r2dbc:mysql://user:pass@localhost:3306/mydb")
```

`runIfLeader` 최초 호출 시 스키마가 자동으로 생성됩니다.

### 코루틴 단일 리더

```kotlin
val election = ExposedR2dbcSuspendLeaderElector(db)

val result = election.runIfLeader("daily-report") {
    delay(100)
    generateReport()
}
// 선출된 노드에서는 report 반환, 나머지 노드에서는 null 반환
```

### 코루틴 복수 리더 그룹

```kotlin
val options = ExposedR2dbcLeaderGroupElectionOptions(
    leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3)
)
val election = ExposedR2dbcSuspendLeaderGroupElector(db, options)

coroutineScope {
    val jobs = (1..10).map {
        async {
            election.runIfLeader("parallel-batch") {
                processChunk(it)
            }
        }
    }
    jobs.awaitAll()
    // 최대 3개 동시 실행, 나머지는 null 반환
}
```

### 확장 함수

```kotlin
// 단일 리더 축약 함수
val report = db.suspendRunIfLeader("daily-report") {
    generateReport()
} ?: return  // 선출 실패 시 건너뜀

// 복수 리더 축약 함수
val result = db.suspendRunIfLeaderGroup("worker-pool") {
    processChunk()
}
```

### 커스텀 옵션

```kotlin
val options = ExposedR2dbcLeaderElectionOptions(
    leaderOptions = LeaderElectionOptions(
        waitTime = 3.seconds,
        leaseTime = 30.seconds,
    ),
    retryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
    recordHistory = true,
    lockOwner = "worker-1",
)
val election = ExposedR2dbcSuspendLeaderElector(db, options)
```

### SPI 팩토리 사용

```kotlin
val factory: SuspendLeaderElectorFactory = ExposedR2DbcSuspendLeaderElectorFactory(db)

coroutineScope {
    val elector = factory.create(LeaderElectionOptions.Default)
    val result = elector.runIfLeader("daily-job") { generateReport() }
}
```

```kotlin
val groupFactory: SuspendLeaderGroupElectorFactory = ExposedR2DbcSuspendLeaderGroupElectorFactory(db)

coroutineScope {
    val elector = groupFactory.create(LeaderGroupElectionOptions(maxLeaders = 3))
    val result = elector.runIfLeader("parallel-batch") { processChunk() }
}
```

## 락 전략

### 단일 리더 (`ExposedR2dbcLock`)

획득 시도마다 단일 트랜잭션으로 처리:

1. **UPDATE** — 만료된 행 갱신: `UPDATE SET token=?, lockedUntil=? WHERE lockName=? AND lockedUntil < NOW()`
2. **INSERT IGNORE** — 행이 없으면 신규 삽입 (충돌 시 무시)
3. **SELECT** — 현재 인스턴스 token 소유 여부 확인

unlock은 token이 일치하는 경우에만 행을 삭제 — zombie unlock 방지.

### 복수 리더 (`ExposedR2dbcGroupLock`)

`(lockName, slot)` 복합 PK로 키잉하는 동일 전략. 슬롯 하나하나가 독립 락이며, `maxLeaders`가 슬롯 수를 결정합니다.

### 데이터베이스 호환성

| 데이터베이스 | INSERT 전략 | 비고 |
|-------------|------------|------|
| PostgreSQL | `INSERT ... ON CONFLICT DO NOTHING` | 완전 지원 |
| MySQL 8 | `INSERT IGNORE INTO` | 완전 지원 |
| H2 | `INSERT IGNORE INTO` (MySQL 모드) | URL에 `MODE=MySQL` 필수 |

## 재시도 전략

| 전략 | 설명 |
|------|------|
| `RetryStrategy.Jitter(baseDelayMs)` | AWS full-jitter: `random(0, baseDelay * attempt)` |
| `RetryStrategy.Fixed(fixedMs)` | 시도 간 고정 대기 |
| `RetryStrategy.Exponential(baseMs, factor)` | 지수 백오프 (jitter 선택 적용) |

## 스키마

테이블은 `leader-exposed-core`에 정의되며, `ExposedR2dbcSchemaInitializer.ensureSchema(db)`로 생성됩니다 (자동 호출됨):

| 테이블 | 용도 |
|--------|------|
| `leader_lock` | 단일 리더 락 행 |
| `leader_group_lock` | 복수 리더 슬롯 행 |
| `leader_lock_history` | 감사 로그 (`recordHistory = true` 시에만) |

## 의존성

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc:0.1.0-SNAPSHOT")

// R2DBC 드라이버 — 하나를 선택:
runtimeOnly("io.r2dbc:r2dbc-h2:1.x")
runtimeOnly("org.postgresql:r2dbc-postgresql:1.x")
runtimeOnly("io.asyncer:r2dbc-mysql:1.x")
```

> H2는 `INSERT IGNORE` 지원을 위해 R2DBC URL에 `MODE=MySQL`을 설정해야 합니다.
