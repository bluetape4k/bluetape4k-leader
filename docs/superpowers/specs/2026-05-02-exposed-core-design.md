# leader-exposed-core 설계 스펙

> Issue: https://github.com/bluetape4k/bluetape4k-leader/issues/23
> Date: 2026-05-02
> Status: Review-Complete

---

## 1. 개요 및 목적

### 역할

`leader-exposed-core`는 JetBrains Exposed ORM의 **스키마 정의 전용** 모듈입니다.
RDBMS 기반 분산 리더 선출에 필요한 테이블 정의, 상수, 공통 예외 타입을 제공합니다.

이 모듈은 **JDBC 드라이버나 R2DBC 드라이버를 포함하지 않습니다.**
실제 락 획득/해제 로직은 하위 모듈에서 구현합니다.

### JDBC / R2DBC 분리 전략

```
leader-exposed-core          ← 스키마 정의만 (exposed-core, exposed-java-time)
├── leader-exposed-jdbc      ← JDBC 구현 (exposed-jdbc, HikariCP)
└── leader-exposed-r2dbc     ← R2DBC 구현 (exposed-r2dbc, r2dbc-pool)
```

| 계층 | 의존성 | 역할 |
|------|--------|------|
| `leader-exposed-core` | `exposed-core`, `exposed-java-time` | Table 객체, 상수, 예외 |
| `leader-exposed-jdbc` | `exposed-jdbc`, HikariCP, JDBC 드라이버 | `LeaderElection` + `LeaderGroupElection` 구현 |
| `leader-exposed-r2dbc` | `exposed-r2dbc`, R2DBC 드라이버 | `SuspendLeaderElection` + `SuspendLeaderGroupElection` 구현 |

### 범위 (Scope)

이 모듈에 **포함**:
- `LeaderLockTable` 정의
- `LeaderGroupLockTable` 정의
- `LeaderLockHistoryTable` 정의
- `ExposedLeaderConstants` 상수
- `ExposedLeaderExceptions` 예외 타입 (선택)

이 모듈에 **불포함**:
- 락 획득/해제 로직 (JDBC/R2DBC 모듈)
- 트랜잭션 관리 (JDBC/R2DBC 모듈)
- Connection pool 설정 (JDBC/R2DBC 모듈)

---

## 2. 모듈 구조 (파일 목록)

```
leader-exposed-core/
├── build.gradle.kts
└── src/
    ├── main/kotlin/io/bluetape4k/leader/exposed/
    │   ├── tables/
    │   │   ├── LeaderLockTable.kt            — 단일 리더 락 테이블
    │   │   ├── LeaderGroupLockTable.kt       — 그룹 리더 락 테이블
    │   │   └── LeaderLockHistoryTable.kt     — 선출 이력 테이블
    │   ├── ExposedLeaderConstants.kt         — 테이블명, 컬럼 길이 등 상수
    │   ├── ExposedLeaderSchema.kt            — SchemaUtils 헬퍼 (create/drop)
    │   └── ExposedLeaderExceptions.kt        — Exposed 백엔드 전용 예외 (선택)
    └── test/
        ├── kotlin/io/bluetape4k/leader/exposed/
        │   ├── tables/
        │   │   ├── LeaderLockTableTest.kt
        │   │   ├── LeaderGroupLockTableTest.kt
        │   │   └── LeaderLockHistoryTableTest.kt
        │   ├── AbstractExposedTableTest.kt   — 공통 테스트 인프라
        │   └── ExposedLeaderSchemaTest.kt    — Schema create/drop 테스트
        └── resources/
            ├── junit-platform.properties     — PER_CLASS + parallel=false
            └── logback-test.xml
```

---

## 3. 스키마 설계

### 3.1 LeaderLockTable (`bluetape4k_leader_locks`)

단일 리더 선출에 사용하는 메인 락 테이블입니다.
MongoDB `MongoLock`의 RDBMS 등가물입니다.

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| `lock_name` | VARCHAR(255) | **PK** | 락 식별자 (e.g. `"daily-report"`) |
| `lock_owner` | VARCHAR(255) | nullable | 인스턴스 식별자 (hostname, pod name 등) |
| `token` | VARCHAR(36) | NOT NULL | UUID 토큰 — 중복 해제 방지 (fencing token) |
| `locked_at` | TIMESTAMP | NOT NULL | 락 획득 시각 (`java.time.Instant`) |
| `locked_until` | TIMESTAMP | NOT NULL | 락 만료 시각 (`java.time.Instant`) |

```kotlin
package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 단일 리더 선출에 사용하는 분산 락 테이블입니다.
 *
 * - `lock_name` PK로 1:1 리더 선출을 보장합니다.
 * - `token`은 UUID 기반 fencing token으로, 만료 후 재획득한 다른 인스턴스가
 *   원래 보유자의 unlock 요청을 무시할 수 있게 합니다 (MongoDB `MongoLock` 패턴).
 * - `locked_until` 기반으로 만료 판정: `WHERE locked_until < NOW()` 조건으로
 *   새 리더가 원자적으로 갱신합니다.
 */
object LeaderLockTable : Table(LOCK_TABLE_NAME) {

    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()
    val token = varchar("token", TOKEN_LENGTH)
    val lockedAt = timestamp("locked_at")
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName)
}
```

**설계 근거:**
- `lockName` PK: MongoDB `_id` = lockKey 패턴과 동일. 자연키(natural key) 사용으로 JOIN/조회 단순화
- `token` (UUID, **NOT NULL**): `MongoLock`의 토큰 기반 중복 해제 방지 패턴을 RDBMS에 적용.
  `unlock()` 시 `WHERE lock_name = ? AND token = ?` 조건으로 타 인스턴스의 락을 실수로 해제하는 것을 방지. 구현체에서는 반드시
  `java.util.Base58.randomString(8)` (SecureRandom 기반)을 사용해야 합니다.
- `locked_until`: TTL 인덱스 대신 SQL `WHERE locked_until < CURRENT_TIMESTAMP` 조건으로 만료 판정

> ⚠️ **`locked_until` 설정 주의:** `locked_until`은 애플리케이션의 `Instant.now() + leaseTime`으로 계산됩니다.
> `leaseTime`을 너무 짧게 설정하면 action 실행 중 락이 만료될 수 있습니다.
> `leaseTime >= action 최대 실행 시간 × 1.5`를 권장합니다.

### 3.2 LeaderGroupLockTable (`bluetape4k_leader_group_locks`)

세마포어 기반 복수 리더 선출에 사용하는 그룹 락 테이블입니다.
MongoDB `${lockName}:slot:N` 패턴의 RDBMS 등가물입니다.

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| `lock_name` | VARCHAR(255) | **복합 PK** | 그룹 락 식별자 |
| `slot` | INT | **복합 PK** | 슬롯 번호 (0 ~ maxLeaders-1) |
| `lock_owner` | VARCHAR(255) | nullable | 인스턴스 식별자 |
| `token` | VARCHAR(36) | NOT NULL | UUID 토큰 |
| `locked_at` | TIMESTAMP | NOT NULL | 슬롯 획득 시각 |
| `locked_until` | TIMESTAMP | NOT NULL | 슬롯 만료 시각 |

```kotlin
package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 세마포어 기반 복수 리더 그룹 선출에 사용하는 분산 락 테이블입니다.
 *
 * - 복합 PK `(lock_name, slot)`로 lockName당 maxLeaders개의 독립 슬롯을 지원합니다.
 * - MongoDB `${lockName}:slot:N` 키 패턴과 동일한 의미입니다.
 * - 각 슬롯은 `LeaderLockTable`과 동일한 token 기반 fencing을 적용합니다.
 */
object LeaderGroupLockTable : Table(GROUP_LOCK_TABLE_NAME) {

    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)
    val slot = integer("slot")
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()
    val token = varchar("token", TOKEN_LENGTH)
    val lockedAt = timestamp("locked_at")
    val lockedUntil = timestamp("locked_until")

    override val primaryKey = PrimaryKey(lockName, slot)
}
```

**설계 근거:**
- 복합 PK `(lockName, slot)`: MongoDB에서는 `_id = "${lockName}:slot:${slot}"` 문자열 연결이지만, RDBMS에서는 정규화된 복합 키를 사용하여 slot 범위 질의(`WHERE slot BETWEEN 0 AND ?`)를 효율적으로 지원
- `slot` INT: 0-based 인덱스. `maxLeaders` 값은 애플리케이션에서 관리

### 3.3 LeaderLockHistoryTable (`bluetape4k_leader_lock_history`)

선출 이력을 보관하는 감사(audit) 테이블입니다.
운영 모니터링, 디버깅, 분석 용도로 사용합니다.

| 컬럼 | 타입 | 제약조건 | 설명 |
|------|------|----------|------|
| `id` | BIGINT | **PK, AUTO_INCREMENT** | 이력 레코드 ID |
| `lock_name` | VARCHAR(255) | NOT NULL | 락 식별자 |
| `lock_owner` | VARCHAR(255) | nullable | 인스턴스 식별자 |
| `token` | VARCHAR(36) | NOT NULL | 락 획득 시 발급된 UUID token — EXPIRED 식별에 사용 |
| `slot` | INT | nullable | 그룹 락 슬롯 번호 (단일 리더 락은 null) |
| `locked_until` | TIMESTAMP | NOT NULL | 이 락 획득의 만료 시각 — `locked_until < NOW()` 로 EXPIRED 판정 |
| `status` | VARCHAR(20) | NOT NULL | `ACQUIRED`, `COMPLETED`, `FAILED`, `EXPIRED` |
| `started_at` | TIMESTAMP | NOT NULL | 선출 시작 시각 |
| `finished_at` | TIMESTAMP | nullable | 작업 완료 시각 |
| `duration_ms` | BIGINT | nullable | 작업 소요 시간 (ms) — 완료 시 자동 계산 |

**인덱스:**
- `idx_history_lock_started` ON (`lock_name`, `started_at`) — 특정 락의 시간순 이력 조회

```kotlin
package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_HISTORY_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_NAME_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_OWNER_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.STATUS_LENGTH
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.TOKEN_LENGTH
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 리더 선출 이력을 보관하는 감사(audit) 테이블입니다.
 *
 * - 1개월치 이력을 보관하여 운영 모니터링, 디버깅, 분석에 활용합니다.
 * - `token`으로 락 테이블과의 1:1 대응을 식별하고, ACQUIRED → EXPIRED 전환 시 정확한 이력을 찾을 수 있습니다.
 * - `slot`은 그룹 락에만 사용됩니다 (단일 리더 락은 null).
 * - `locked_until < NOW()` 조건으로 이 이력 항목의 만료 여부를 판정합니다.
 * - 30일 이상 경과한 데이터는 애플리케이션 레벨에서 주기적으로 삭제합니다.
 * - `duration_ms`는 `finished_at - started_at` 기반으로 JDBC/R2DBC 구현체에서 계산합니다.
 */
object LeaderLockHistoryTable : Table(LOCK_HISTORY_TABLE_NAME) {

    val id = long("id").autoIncrement()
    val lockName = varchar("lock_name", LOCK_NAME_LENGTH)
    val lockOwner = varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()
    val token = varchar("token", TOKEN_LENGTH)         // NOT NULL — ACQUIRED 시점의 fencing token
    val slot = integer("slot").nullable()              // 그룹 락 슬롯 (단일 리더 락은 null)
    val lockedUntil = timestamp("locked_until")        // 이 획득의 만료 시각 — EXPIRED 판정 기준
    val status = varchar("status", STATUS_LENGTH)
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    val durationMs = long("duration_ms").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "idx_history_lock_started", isUnique = false, lockName, startedAt)
        index(customIndexName = "idx_history_token", isUnique = false, token)
    }
}
```

**이력 상태(Status) 라이프사이클:**

```
tryLock() 성공 → ACQUIRED
  ├── action() 성공 → COMPLETED (durationMs = finishedAt - startedAt)
  ├── action() 실패 → FAILED    (durationMs = finishedAt - startedAt)
  └── leaseTime 초과  → EXPIRED   (finishedAt = null, durationMs = null)
```

**상태 전환 책임 (State Transition Ownership):**

| 전환 | 주체 | 시점 |
|------|------|------|
| → ACQUIRED | `leader-exposed-jdbc` / `leader-exposed-r2dbc` | tryLock 성공 직후 이력 INSERT |
| ACQUIRED → COMPLETED | 구현 모듈 | action 완료 시 이력 UPDATE |
| ACQUIRED → FAILED | 구현 모듈 | action 예외 발생 시 이력 UPDATE |
| ACQUIRED → EXPIRED | **Lazy 방식**: 다음 tryLock 시 감지 (권장) | locked_until < NOW() 조건 탐지 시 |

> `leader-exposed-core`는 스키마만 정의합니다.
> 상태 전환 로직은 **구현 모듈(`leader-exposed-jdbc`, `leader-exposed-r2dbc`) 책임**입니다.
> EXPIRED 전환은 Active cleanup(스케줄러)과 Lazy expiry(tryLock 시 감지) 중 구현 모듈에서 선택합니다.

**TTL 정책:**
- 30일 이상 된 데이터를 애플리케이션 레벨에서 주기적으로 삭제
- 삭제 쿼리: `DELETE FROM bluetape4k_leader_lock_history WHERE started_at < NOW() - INTERVAL '30 days'`
- 정리 주체는 구현 모듈 또는 외부 스케줄러:
  1. Spring Boot 모듈에서 `@Scheduled` 기반 정리 작업 등록
  2. DB-native 스케줄러 (MySQL EVENT, pg_cron)
  3. 외부 운영 도구 (cron + SQL script)

### 3.4 HistoryStatus 열거형

```kotlin
package io.bluetape4k.leader.exposed.tables

/**
 * 리더 선출 이력의 상태를 정의하는 열거형입니다.
 */
enum class HistoryStatus {
    /** 락 획득 성공 — action 실행 시작 */
    ACQUIRED,

    /** action 실행 완료 (정상) */
    COMPLETED,

    /** action 실행 실패 (예외 발생) */
    FAILED,

    /** leaseTime 초과로 만료됨 */
    EXPIRED,
}
```

---

## 4. 공통 상수

```kotlin
package io.bluetape4k.leader.exposed

/**
 * Exposed 백엔드 테이블/컬럼에서 사용하는 공통 상수입니다.
 */
object ExposedLeaderConstants {

    /** 단일 리더 락 테이블명 */
    const val LOCK_TABLE_NAME = "bluetape4k_leader_locks"

    /** 그룹 리더 락 테이블명 */
    const val GROUP_LOCK_TABLE_NAME = "bluetape4k_leader_group_locks"

    /** 선출 이력 테이블명 */
    const val LOCK_HISTORY_TABLE_NAME = "bluetape4k_leader_lock_history"

    /** lock_name 컬럼 최대 길이 */
    const val LOCK_NAME_LENGTH = 255

    /** lock_owner 컬럼 최대 길이 */
    const val LOCK_OWNER_LENGTH = 255

    /** token 컬럼 최대 길이 (UUID = 36자) */
    const val TOKEN_LENGTH = 36

    /** status 컬럼 최대 길이 */
    const val STATUS_LENGTH = 20
}
```

---

## 5. ExposedLeaderSchema (Migration/SchemaUtils 헬퍼)

`leader-exposed-jdbc`, `leader-exposed-r2dbc` 구현 모듈이 테이블을 생성/삭제할 때 사용하는
SchemaUtils 래퍼입니다.

```kotlin
package io.bluetape4k.leader.exposed

import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import org.jetbrains.exposed.v1.core.Table

/**
 * leader-exposed 모듈에서 사용하는 모든 테이블과 SchemaUtils 헬퍼를 제공합니다.
 *
 * 구현 모듈(leader-exposed-jdbc, leader-exposed-r2dbc)에서 앱 시작 시
 * `ExposedLeaderSchema.createMissingTablesAndColumns(database)` 를 호출하세요.
 */
object ExposedLeaderSchema {

    /** 모든 leader 관련 테이블 배열 */
    val allTables: Array<Table> = arrayOf(
        LeaderLockTable,
        LeaderGroupLockTable,
        LeaderLockHistoryTable,
    )
}
```

> **왜 `ExposedLeaderSchema`가 필요한가?**
> 구현 모듈에서 `SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)`를 호출하면
> 모든 테이블을 일관되게 생성할 수 있습니다.
> `allTables`를 직접 참조함으로써 새 테이블 추가 시 구현 모듈을 수정할 필요가 없습니다.
>
> 실제 `SchemaUtils.create()` / `createMissingTablesAndColumns()` 호출은 트랜잭션 컨텍스트가 필요하므로
> `leader-exposed-core`는 `allTables` 목록만 제공하고, 호출은 구현 모듈 책임으로 둡니다.

---

## 6. leader-core 변경 사항

### validateLockName() 이동

현재 `validateLockName()`은 `leader-mongodb` 모듈의 `MongoLock.kt` 파일 하단에
`internal` 최상위 함수로 정의되어 있습니다.

```kotlin
// leader-mongodb/.../lock/MongoLock.kt (현재 위치)
internal fun validateLockName(lockName: String) {
    require(lockName.isNotBlank()) { "lockName must not be blank" }
    require(!lockName.contains('.')) { "lockName must not contain '.': $lockName" }
    require(!lockName.contains(":slot:")) { "lockName must not contain ':slot:': $lockName" }
}
```

이 함수를 `leader-core`로 이동하여 모든 백엔드에서 공유합니다:

```kotlin
// leader-core/.../LockNameValidator.kt (이동 후)
package io.bluetape4k.leader

/**
 * lockName 유효성 검증 함수입니다.
 *
 * 모든 백엔드 구현체에서 공통으로 사용합니다.
 * - blank 불허
 * - 최대 255자
 * - 허용 문자: `[a-zA-Z0-9_\-:]` (소문자/대문자/숫자/언더스코어/하이픈/콜론)
 * - '.' 포함 불허 (MongoDB 필드 이름 제약 호환)
 * - ':slot:' 포함 불허 (그룹 슬롯 키 충돌 방지 — MongoDB `${lockName}:slot:N` 패턴)
 *
 * @param lockName 검증할 락 이름
 * @throws IllegalArgumentException 유효하지 않은 lockName
 */

// 첫 문자 1자 + 이후 0~254자 = 최대 255자
private val LOCK_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-:]{0,254}$")

fun validateLockName(lockName: String) {
    require(lockName.isNotBlank()) { "lockName must not be blank" }
    require(lockName.length <= 255) { "lockName must not exceed 255 characters: length=${lockName.length}" }
    require(LOCK_NAME_PATTERN.matches(lockName)) {
        "lockName contains invalid characters. Allowed: [a-zA-Z0-9_\\-:], got: $lockName"
    }
    require(!lockName.contains(":slot:")) { "lockName must not contain ':slot:': $lockName" }
}
```

> **화이트리스트 채택 이유:** lockName은 RDBMS PK로 직접 사용됩니다.
> Prepared statement를 사용하므로 SQL injection 위험은 낮지만,
> 예상치 못한 특수문자로 인한 운영 혼선과 로그 파싱 문제를 예방합니다.
>
> **Breaking Change 여부:** 기존 MongoDB 사용자가 `.`(점) 포함 lockName을 사용하고 있었다면 이 변경으로 거부됩니다.
> 이는 **의도적인 breaking change**입니다. `.`은 MongoDB 필드 경로 구분자와 충돌하므로 원래부터 금지되어 있었습니다.
> 단, 영숫자/언더스코어/하이픈/콜론 외의 문자(공백, `@`, `#` 등)는 이 정규식에 의해 새로 거부됩니다.
>
> **회귀 테스트 요구사항:** `leader-mongodb`의 기존 테스트에서 사용하는 lockName이 새 화이트리스트를 통과하는지
> `leader-core`로 이동 후 확인 필요.
>
> **`:slot:` 금지 유지 이유:** `leader-core`로 공통화 시 MongoDB 백엔드 호환성을 위해 유지합니다.
> RDBMS 백엔드에서는 slot이 별도 컬럼으로 분리되므로 이 규칙은 중복이지만, 전체 일관성을 위해 유지합니다.

**변경 후 영향:**
- `leader-mongodb`의 `MongoLock.kt`, `MongoSuspendLock.kt` 등에서 import 경로 변경:
  `io.bluetape4k.leader.mongodb.lock.validateLockName` -> `io.bluetape4k.leader.validateLockName`
- `leader-exposed-jdbc`, `leader-exposed-r2dbc`에서 즉시 사용 가능

---

## 7. build.gradle.kts 변경 사항

### 6.1 leader-exposed-core/build.gradle.kts

```kotlin
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))

    // Exposed core (스키마 정의 — JDBC/R2DBC 드라이버 없음)
    api(libs.exposed.core)
    api(libs.exposed.java.time)
    compileOnly(libs.exposed.dao)

    // Test — Multi-DB (H2, PostgreSQL, MySQL)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.exposed.jdbc.tests)

    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.hikaricp)

    // H2 (in-memory, 빠른 단위 테스트)
    testImplementation(libs.h2.v2)

    // PostgreSQL (Testcontainers)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    // MySQL (Testcontainers)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.testcontainers.mysql)
}
```

### 6.2 gradle/libs.versions.toml 추가 항목

기존 `[libraries]` 섹션에 다음을 추가합니다:

```toml
# Exposed — java.time 지원
exposed-java-time = { module = "org.jetbrains.exposed:exposed-java-time", version.ref = "exposed" }

# bluetape4k — Exposed JDBC 테스트 유틸리티
bluetape4k-exposed-jdbc-tests = { module = "io.github.bluetape4k:bluetape4k-exposed-jdbc-tests" }

# H2 Database (테스트용)
h2-v2 = { module = "com.h2database:h2", version = "2.4.240" }

# MySQL Connector/J (테스트용)
mysql-connector-j = { module = "com.mysql:mysql-connector-j", version = "9.6.0" }

# Testcontainers — MySQL
testcontainers-mysql = { module = "org.testcontainers:mysql", version.ref = "testcontainers" }
```

---

## 8. 테스트 전략

### 7.1 테스트 인프라

`bluetape4k-exposed-jdbc-tests` 라이브러리에서 제공하는 테스트 유틸리티를 사용합니다:

- `TestDB` enum: `H2`, `POSTGRESQL`, `MYSQL_V8`
- `withTables(testDB, *tables) { ... }`: 테이블 생성 → 트랜잭션 실행 → 테이블 삭제
- `@ParameterizedTest @MethodSource("enableDialects")`: 다중 DB 파라미터화 테스트

### 7.2 AbstractExposedTableTest (공통 베이스)

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractExposedTableTest {

    companion object : KLogging() {
        @JvmStatic
        fun enableDialects(): List<TestDB> = listOf(TestDB.H2, TestDB.POSTGRESQL, TestDB.MYSQL_V8)
    }
}
```

> **주의:** `@JvmStatic`은 반드시 `companion object { }` 블록 **내부**에 위치해야 합니다.
> JUnit 5 `@MethodSource`는 static 메서드를 요구하므로, companion object 밖에 두면
> `No factory method found` 예외가 발생합니다.

### 7.3 테스트 케이스 (테이블별)

#### LeaderLockTableTest

| 테스트 | 설명 |
|--------|------|
| `테이블 생성 및 삭제가 성공한다` | `withTables` 내에서 DDL 실행 확인 |
| `락 레코드 삽입 및 조회가 성공한다` | INSERT → SELECT by PK |
| `동일 lockName 중복 삽입 시 예외가 발생한다` | PK 중복 → exception |
| `만료된 락을 갱신할 수 있다` | `WHERE locked_until < NOW()` UPDATE |
| `token 불일치 시 삭제되지 않는다` | DELETE WHERE token != 조건 확인 |
| `timestamp 정밀도가 보존된다` | H2 vs PostgreSQL vs MySQL 정밀도 차이 확인 |

#### LeaderGroupLockTableTest

| 테스트 | 설명 |
|--------|------|
| `테이블 생성 및 삭제가 성공한다` | DDL 확인 |
| `복합 PK (lockName, slot) 삽입이 성공한다` | 동일 lockName, 다른 slot |
| `동일 (lockName, slot) 중복 삽입 시 예외가 발생한다` | 복합 PK 중복 |
| `활성 슬롯은 locked_until >= NOW() 조건으로만 카운트한다` | 만료된 row는 available로 계산됨 확인 |
| `만료된 슬롯은 신규 획득 가능하다` | `WHERE slot = ? AND locked_until < NOW()` UPDATE |
| `특정 slot 범위 질의가 가능하다` | `WHERE slot BETWEEN 0 AND ?` |

> **활성 슬롯 계산 기준:** `COUNT(*) WHERE lock_name = ? AND locked_until >= NOW()`
> RDBMS는 Redis/MongoDB TTL 자동 만료가 없으므로, 만료된 row도 물리적으로 존재합니다.
> 활성 슬롯 수는 반드시 `locked_until >= NOW()` 조건을 포함해야 합니다.

#### LeaderLockHistoryTableTest

| 테스트 | 설명 |
|--------|------|
| `테이블 생성 및 삭제가 성공한다` | DDL 확인 |
| `이력 레코드 삽입이 성공한다` | 각 status별 INSERT |
| `id가 자동 증가한다` | 연속 INSERT 후 id 비교 |
| `lockName + startedAt 인덱스를 활용한 조회가 성공한다` | 인덱스 기반 조회 |
| `finishedAt, durationMs nullable 컬럼이 정상 동작한다` | ACQUIRED 상태 (null 필드) |
| `30일 이전 데이터 삭제가 성공한다` | 정리 쿼리 검증 |

### 7.4 DB별 주의사항

| DB | TIMESTAMP 정밀도 | AUTO_INCREMENT | 비고 |
|----|------------------|----------------|------|
| H2 | 나노초 | `IDENTITY` | 인메모리, 빠름 |
| PostgreSQL | 마이크로초 | `SERIAL` / `BIGSERIAL` | Testcontainers `postgres:16-alpine` |
| MySQL 8 | 마이크로초 (TIMESTAMP(6)) | `AUTO_INCREMENT` | Testcontainers `mysql:8.0` |

---

## 9. Exposed API 참고 (exposed-java-time)

Exposed 1.2.0의 `exposed-java-time` 모듈은 `java.time.Instant` 매핑을 위해
`timestamp()` 컬럼 타입을 제공합니다.

```kotlin
import org.jetbrains.exposed.v1.javatime.timestamp

// java.time.Instant ↔ SQL TIMESTAMP
val lockedAt = timestamp("locked_at")

// 사용 예시
LeaderLockTable.insert {
    it[lockName] = "daily-job"
    it[token] = Base58.randomString(8)
    it[lockedAt] = Instant.now()
    it[lockedUntil] = Instant.now().plus(Duration.ofSeconds(60))
}
```

> **주의:** `exposed-kotlin-datetime`은 `kotlinx.datetime.Instant`를 사용합니다.
> 이 프로젝트는 `java.time.Instant`을 표준으로 사용하므로 `exposed-java-time`을 채택합니다.

---

## 10. MongoDB 패턴과의 대응 관계

| MongoDB (MongoLock) | RDBMS (Exposed) | 비고 |
|---------------------|-----------------|------|
| `_id` (lockKey) | `lock_name` PK | 자연키 |
| `token` (UUID) | `token` VARCHAR(36) | Fencing token |
| `expireAt` (Date) | `locked_until` (Instant) | 만료 시각 |
| TTL Index (자동 삭제) | `WHERE locked_until < NOW()` | 수동 만료 판정 |
| `findOneAndUpdate` upsert | `INSERT ... ON CONFLICT UPDATE` | DB별 다름 |
| E11000 Duplicate Key | PK violation exception | 재시도 트리거 |
| Collection per type | Table per type | 1:1 대응 |
| `${lockName}:slot:${N}` | 복합 PK `(lock_name, slot)` | 그룹 슬롯 |

---

## 11. 운영 요구사항 및 한계

### 10.1 시각 동기화 요구사항

`locked_until`은 **애플리케이션의 `Instant.now() + leaseTime`** 으로 계산되며,
만료 판정은 **DB 서버의 `NOW()`** 기준으로 수행됩니다.

> ⚠️ 애플리케이션 서버와 DB 서버의 시각 차이(clock drift)가 leaseTime의 10%를 초과하면
> 조기 만료(early expiry) 또는 초과 보유(extended hold)가 발생할 수 있습니다.

**요구사항:**
- 모든 노드(App + DB)에 NTP 동기화 적용 (drift < 1초 권장)
- 대안: `locked_until` 계산을 DB 서버 시각 기반으로 변경 (`INSERT ... SET locked_until = NOW() + interval`)
  — 이 경우 구현 모듈에서 DB-side 시각 계산 필요

### 10.2 Fencing Token 한계 (IMPORTANT)

`token` 기반 fencing은 **동일 lockName에 대한 zombie unlock**을 방지합니다.
그러나 다음 시나리오에서 동시 실행(split-brain)이 발생할 수 있습니다:

```
시나리오:
  T=0  : A가 락 획득 (token=T1, lockedUntil=T+60s)
  T=61 : lockedUntil 만료 → B가 락 재획득 (token=T2)
  T=65 : A의 action이 아직 실행 중 → A와 B 동시 작업 발생
```

**대응 방안:**
1. `leaseTime`을 action 최대 실행 시간의 **2~3배**로 설정
2. action 내부에서 fencing token을 외부 시스템(DB, 캐시)에 전달하여 검증 (Chubby 패턴)
3. **멱등(idempotent) action 설계** — 동시 실행이 일어나도 결과가 동일하도록 보장

> `locked_until` 기반 분산 락은 **best-effort mutual exclusion**을 제공합니다.
> 강한 배타성이 필요하면 Redis RedLock 또는 ZooKeeper 기반 구현을 고려하세요.

---

## 12. JDBC 구현 모듈 선행 설계 (참고)

`leader-exposed-jdbc` 구현체는 다음과 같은 쿼리 패턴을 사용할 것입니다.
이 내용은 참고용이며, `leader-exposed-core` 스펙 범위를 초과합니다.

### tryLock (SELECT FOR UPDATE + INSERT/UPDATE)

```sql
-- 1) 만료된 락 갱신 시도
UPDATE bluetape4k_leader_locks
SET token = ?, lock_owner = ?, locked_at = NOW(), locked_until = ?
WHERE lock_name = ? AND locked_until < NOW();

-- 2) rows == 0이면 INSERT 시도
INSERT INTO bluetape4k_leader_locks (lock_name, lock_owner, token, locked_at, locked_until)
VALUES (?, ?, ?, NOW(), ?)
ON CONFLICT (lock_name) DO NOTHING;  -- PostgreSQL
-- ON DUPLICATE KEY UPDATE 사용 안 함 (MySQL은 별도 핸들링)
```

### unlock

```sql
DELETE FROM bluetape4k_leader_locks
WHERE lock_name = ? AND token = ?;
```

---

## 13. DoD (Definition of Done)

### 코드 작성

- [ ] `LeaderLockTable` 테이블 정의 (`leader-exposed-core`)
- [ ] `LeaderGroupLockTable` 테이블 정의 (`leader-exposed-core`)
- [ ] `LeaderLockHistoryTable` 테이블 정의 — token, slot, locked_until 컬럼 포함 (`leader-exposed-core`)
- [ ] `HistoryStatus` 열거형 (`leader-exposed-core`)
- [ ] `ExposedLeaderConstants` 상수 정의 (`leader-exposed-core`)
- [ ] `ExposedLeaderSchema` SchemaUtils 헬퍼 — `allTables` 배열 제공 (`leader-exposed-core`)
- [ ] `ExposedLeaderExceptions` 예외 타입 (선택)

### 인프라 변경

- [ ] `validateLockName()` → `leader-core` 이동 + MongoDB import 경로 수정
- [ ] `gradle/libs.versions.toml` — 5개 의존성 추가
- [ ] `leader-exposed-core/build.gradle.kts` — `exposed-java-time` + 테스트 의존성 업데이트

### 테스트

- [ ] H2 테이블 생성/삭제 테스트 통과
- [ ] PostgreSQL (Testcontainers) 테이블 생성/삭제 테스트 통과
- [ ] MySQL 8 (Testcontainers) 테이블 생성/삭제 테스트 통과
- [ ] `ExposedLeaderSchema.allTables`로 `SchemaUtils.createMissingTablesAndColumns` 실행 테스트 통과
- [ ] 각 테이블별 CRUD 테스트 통과
- [ ] PK 중복 삽입 예외 확인
- [ ] timestamp 정밀도 확인 (DB별)
- [ ] 그룹 락 만료 슬롯이 활성 카운트에서 제외됨 확인 (`locked_until >= NOW()` 조건)
- [ ] `LeaderLockHistoryTable` token/slot/locked_until 컬럼 포함 DDL 확인
- [ ] `validateLockName()` 이동 후 기존 MongoDB 테스트 lockName 회귀 확인

### 빌드 & 문서

- [ ] `./gradlew :leader-exposed-core:compileKotlin` 클린
- [ ] `./gradlew :leader-exposed-core:test` 전체 통과
- [ ] `./gradlew :leader-mongodb:test` 회귀 없음 (validateLockName 이동 후)
- [ ] `leader-exposed-core/README.md` 작성
- [ ] `leader-exposed-core/README.ko.md` 작성
- [ ] KDoc 완비 (모든 public API)

---

## 14. 초안 태스크 목록

| # | 태스크 | 우선순위 | 의존성 |
|---|--------|----------|--------|
| 1 | `gradle/libs.versions.toml`에 5개 의존성 추가 | HIGH | - |
| 2 | `leader-exposed-core/build.gradle.kts` 업데이트 | HIGH | #1 |
| 3 | `ExposedLeaderConstants.kt` 작성 | HIGH | - |
| 4 | `HistoryStatus.kt` 작성 | MEDIUM | - |
| 5 | `LeaderLockTable.kt` 작성 | HIGH | #2, #3 |
| 6 | `LeaderGroupLockTable.kt` 작성 | HIGH | #2, #3 |
| 7 | `LeaderLockHistoryTable.kt` 작성 (token/slot/locked_until 포함) | HIGH | #2, #3, #4 |
| 8 | `ExposedLeaderSchema.kt` 작성 (allTables 헬퍼) | HIGH | #5, #6, #7 |
| 9 | `validateLockName()` → `leader-core` 이동 (regex + 회귀 테스트) | MEDIUM | - |
| 10 | `leader-mongodb` import 경로 수정 (회귀 확인) | MEDIUM | #9 |
| 11 | `AbstractExposedTableTest.kt` 작성 | HIGH | #2 |
| 12 | `LeaderLockTableTest.kt` 작성 | HIGH | #5, #11 |
| 13 | `LeaderGroupLockTableTest.kt` 작성 (만료 슬롯 테스트 포함) | HIGH | #6, #11 |
| 14 | `LeaderLockHistoryTableTest.kt` 작성 | HIGH | #7, #11 |
| 15 | `ExposedLeaderSchemaTest.kt` 작성 | HIGH | #8, #11 |
| 16 | `compileKotlin` 클린 확인 | HIGH | #5, #6, #7, #8 |
| 17 | 전체 테스트 실행 (H2 + PG + MySQL) | HIGH | #12, #13, #14, #15 |
| 18 | MongoDB 모듈 회귀 테스트 | MEDIUM | #10 |
| 19 | README.md + README.ko.md 작성 | LOW | #17 |

---

## 부록 A: exposed-kotlin-datetime vs exposed-java-time

| 구분 | exposed-kotlin-datetime | exposed-java-time |
|------|------------------------|-------------------|
| 시간 타입 | `kotlinx.datetime.Instant` | `java.time.Instant` |
| 아티팩트 | `exposed-kotlin-datetime` | `exposed-java-time` |
| 프로젝트 표준 | - | **채택** |
| 이유 | KMP 지향 | JVM-only 프로젝트, `java.time` 이미 전역 사용 |

`leader-core`의 `LeaderElectionOptions`가 `java.time.Duration`을 사용하므로,
일관성을 위해 `exposed-java-time`을 채택합니다.

## 부록 B: `exposed-kotlin-datetime` 의존성 제거 계획

현재 `build.gradle.kts`에 `exposed-kotlin-datetime`이 있습니다.
`exposed-java-time` 채택 후 `exposed-kotlin-datetime`은 이 모듈에서 제거합니다.

```diff
- api(libs.exposed.kotlin.datetime)
+ api(libs.exposed.java.time)
```
