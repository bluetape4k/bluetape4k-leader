# leader-exposed-core 구현 계획

> Issue: #23 | Spec: docs/superpowers/specs/2026-05-02-exposed-core-design.md | Date: 2026-05-02
> 베이스 브랜치: `develop`
> 워크트리: `.worktrees/feat/issue-23-exposed-core`
> 모듈 패키지: `io.bluetape4k.leader.exposed`

---

## 실행 순서 개요

```
Group 0 (병렬, 선행 없음)
├── T1:  gradle/libs.versions.toml 의존성 추가
├── T2:  ExposedLeaderConstants.kt 상수 정의
├── T3:  HistoryStatus.kt 열거형 작성
└── T16: junit-platform.properties  ← [HIGH-1] T11 실행 전 배치 필수

Group 1 (T1 완료 후)
└── T4: leader-exposed-core/build.gradle.kts 업데이트

Group 2 (T1, T2, T3, T4 완료 후 — T5/T6/T7 병렬)
├── T5: LeaderLockTable.kt
├── T6: LeaderGroupLockTable.kt
└── T7: LeaderLockHistoryTable.kt

Group 3 (T5, T6, T7 완료 후)
└── T8: ExposedLeaderSchema.kt

Group 4 (T1 완료 후, Group 0~3과 독립)
├── T9:  validateLockName() 2-tier 설계 (core + MongoDB)
└── T10: leader-mongodb import 경로 수정 + MongoLock.kt 업데이트 (T9 완료 후)

Group 5 (T4, T16 완료 후)
└── T11: AbstractExposedTableTest.kt 테스트 베이스

Group 6 (T5+T11, T6+T11, T7+T11, T8+T11 완료 후 — 병렬)
├── T12: LeaderLockTableTest.kt
├── T13: LeaderGroupLockTableTest.kt
├── T14: LeaderLockHistoryTableTest.kt
└── T15: ExposedLeaderSchemaTest.kt

Group 7 (T12~T15, T10 완료 후)
├── T17: 전체 빌드 + 테스트 검증
└── T18: MongoDB 모듈 회귀 테스트

Group 8 (T17, T18 완료 후)
└── T19: README.md + README.ko.md 작성
```

---

## 태스크 목록

### T1: gradle/libs.versions.toml 의존성 5개 추가

- **complexity**: low
- **대상 파일**: `gradle/libs.versions.toml`
- **구현 지침**:
  - `[libraries]` 섹션에 아래 5개 항목 추가:
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
  - 기존 `exposed-*` 항목 근처에 `exposed-java-time` 배치
  - 기존 `bluetape4k-*` 항목 근처에 `bluetape4k-exposed-jdbc-tests` 배치
  - 기존 `testcontainers-*` 항목 근처에 `testcontainers-mysql` 배치
  - `h2-v2`, `mysql-connector-j`는 새 JDBC 드라이버 섹션에 배치
- **완료 기준**:
  - [ ] 5개 라이브러리 항목이 `libs.versions.toml`에 추가됨
  - [ ] `./gradlew :leader-exposed-core:dependencies` 실행 시 에러 없음
- **의존**: 없음

---

### T2: ExposedLeaderConstants.kt 상수 정의

- **complexity**: low
- **대상 파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/ExposedLeaderConstants.kt`
- **구현 지침**:
  - `object ExposedLeaderConstants` 생성
  - 상수 목록:
    - `LOCK_TABLE_NAME = "bluetape4k_leader_locks"`
    - `GROUP_LOCK_TABLE_NAME = "bluetape4k_leader_group_locks"`
    - `LOCK_HISTORY_TABLE_NAME = "bluetape4k_leader_lock_history"`
    - `LOCK_NAME_LENGTH = 255`
    - `LOCK_OWNER_LENGTH = 255`
    - `TOKEN_LENGTH = 36`
    - `STATUS_LENGTH = 20`
  - 모든 상수에 KDoc 주석 작성
  - `const val` 사용 (컴파일 타임 상수)
- **완료 기준**:
  - [ ] 7개 상수가 `object ExposedLeaderConstants`에 정의됨
  - [ ] 모든 상수에 KDoc 작성됨
- **의존**: 없음

---

### T3: HistoryStatus.kt 열거형 작성

- **complexity**: low
- **대상 파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/HistoryStatus.kt`
- **구현 지침**:
  - `enum class HistoryStatus` 생성
  - 값: `ACQUIRED`, `COMPLETED`, `FAILED`, `EXPIRED`
  - 각 값에 KDoc 주석:
    - `ACQUIRED`: 락 획득 성공 — action 실행 시작
    - `COMPLETED`: action 실행 완료 (정상)
    - `FAILED`: action 실행 실패 (예외 발생)
    - `EXPIRED`: leaseTime 초과로 만료됨
  - 패키지: `io.bluetape4k.leader.exposed.tables`
- **완료 기준**:
  - [ ] 4개 enum 값이 정의됨
  - [ ] 각 값에 KDoc 주석 작성됨
- **의존**: 없음

---

### T4: leader-exposed-core/build.gradle.kts 업데이트

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/build.gradle.kts`
- **구현 지침**:
  - 기존 파일에서 `exposed-kotlin-datetime` → `exposed-java-time` 교체
  - 테스트 의존성 대폭 추가 (Multi-DB 테스트):
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
  - `libs.versions.toml`의 alias 명과 일치 확인 (하이픈 → 점 변환 규칙)
- **완료 기준**:
  - [ ] `exposed-kotlin-datetime` 제거, `exposed-java-time` 추가됨
  - [ ] H2, PostgreSQL, MySQL 테스트 의존성 모두 추가됨
  - [ ] `bluetape4k-exposed-jdbc-tests` 테스트 의존성 추가됨
  - [ ] `./gradlew :leader-exposed-core:dependencies` 정상 실행
- **의존**: T1

---

### T5: LeaderLockTable.kt 단일 리더 락 테이블 정의

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockTable.kt`
- **구현 지침**:
  - `object LeaderLockTable : Table(LOCK_TABLE_NAME)` 정의
  - 컬럼:
    - `lockName`: `varchar("lock_name", LOCK_NAME_LENGTH)` — PK
    - `lockOwner`: `varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()`
    - `token`: `varchar("token", TOKEN_LENGTH)` — NOT NULL
    - `lockedAt`: `timestamp("locked_at")` — `java.time.Instant`
    - `lockedUntil`: `timestamp("locked_until")` — `java.time.Instant`
  - `override val primaryKey = PrimaryKey(lockName)`
  - import: `org.jetbrains.exposed.v1.core.Table`, `org.jetbrains.exposed.v1.javatime.timestamp`
  - 클래스 KDoc에 설계 의도 기록 (fencing token, 만료 판정 방식)
- **완료 기준**:
  - [ ] 5개 컬럼 + PK 정의됨
  - [ ] `timestamp()` import가 `exposed-java-time`에서 올바르게 참조됨
  - [ ] KDoc 작성됨
- **의존**: T2, T4

---

### T6: LeaderGroupLockTable.kt 그룹 리더 락 테이블 정의

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderGroupLockTable.kt`
- **구현 지침**:
  - `object LeaderGroupLockTable : Table(GROUP_LOCK_TABLE_NAME)` 정의
  - 컬럼:
    - `lockName`: `varchar("lock_name", LOCK_NAME_LENGTH)` — 복합 PK 일부
    - `slot`: `integer("slot")` — 복합 PK 일부
    - `lockOwner`: `varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()`
    - `token`: `varchar("token", TOKEN_LENGTH)` — NOT NULL
    - `lockedAt`: `timestamp("locked_at")`
    - `lockedUntil`: `timestamp("locked_until")`
  - `override val primaryKey = PrimaryKey(lockName, slot)`
  - KDoc에 복합 PK 설계 근거, MongoDB `${lockName}:slot:N` 대응 관계 기록
- **완료 기준**:
  - [ ] 6개 컬럼 + 복합 PK `(lockName, slot)` 정의됨
  - [ ] KDoc 작성됨
- **의존**: T2, T4

---

### T7: LeaderLockHistoryTable.kt 선출 이력 테이블 정의

- **complexity**: high
- **대상 파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockHistoryTable.kt`
- **구현 지침**:
  - `object LeaderLockHistoryTable : Table(LOCK_HISTORY_TABLE_NAME)` 정의
  - 컬럼:
    - `id`: `long("id").autoIncrement()` — PK
    - `lockName`: `varchar("lock_name", LOCK_NAME_LENGTH)` — NOT NULL
    - `lockOwner`: `varchar("lock_owner", LOCK_OWNER_LENGTH).nullable()`
    - `token`: `varchar("token", TOKEN_LENGTH)` — NOT NULL (ACQUIRED 시점의 fencing token)
    - `slot`: `integer("slot").nullable()` — 그룹 락만 사용, 단일 리더 락은 null
    - `lockedUntil`: `timestamp("locked_until")` — NOT NULL, EXPIRED 판정 기준
    - `status`: `varchar("status", STATUS_LENGTH)` — NOT NULL
    - `startedAt`: `timestamp("started_at")` — NOT NULL
    - `finishedAt`: `timestamp("finished_at").nullable()`
    - `durationMs`: `long("duration_ms").nullable()`
  - `override val primaryKey = PrimaryKey(id)`
  - 인덱스 2개 (`init` 블록):
    - `index(customIndexName = "idx_history_lock_started", isUnique = false, lockName, startedAt)`
    - `index(customIndexName = "idx_history_token", isUnique = false, token)`
  - KDoc에 이력 상태 라이프사이클, TTL 정책, token 용도 기록
  - **핵심 설계**: token은 락 획득 시점의 UUID로 EXPIRED 전환 시 정확한 이력 매칭에 사용.
    slot은 그룹 락 전용(단일 리더 락은 null). locked_until은 EXPIRED 판정 기준.
- **완료 기준**:
  - [ ] 10개 컬럼 + PK + 인덱스 2개 정의됨
  - [ ] `token`, `slot`, `locked_until` 컬럼의 nullable/NOT NULL 제약이 스펙과 일치
  - [ ] `init` 블록에 인덱스 2개 정의됨
  - [ ] KDoc 작성됨
- **의존**: T2, T3, T4

---

### T8: ExposedLeaderSchema.kt SchemaUtils 헬퍼

- **complexity**: low
- **대상 파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/ExposedLeaderSchema.kt`
- **구현 지침**:
  - `object ExposedLeaderSchema` 정의
  - `val allTables: Array<Table>` — 3개 테이블 배열:
    ```kotlin
    val allTables: Array<Table> = arrayOf(
        LeaderLockTable,
        LeaderGroupLockTable,
        LeaderLockHistoryTable,
    )
    ```
  - import: `LeaderLockTable`, `LeaderGroupLockTable`, `LeaderLockHistoryTable`, `org.jetbrains.exposed.v1.core.Table`
  - KDoc에 구현 모듈에서 `SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)` 호출 패턴 안내
  - **[HIGH-3]** `Array<Table>` 유지 이유: `SchemaUtils.create(vararg tables: Table)` 등 Exposed API가 vararg를
    받으므로 `*allTables` 스프레드 연산자로 바로 전달 가능. `List<Table>` 대비 복사 없이 O(1)
- **완료 기준**:
  - [ ] `allTables`에 3개 테이블이 순서대로 포함됨
  - [ ] KDoc 작성됨
- **의존**: T5, T6, T7

---

### T9: validateLockName() 2-tier 설계 (leader-core + leader-mongodb)

- **complexity**: high
- **[CRITICAL-1] 2-tier 설계**: common 최소 검증은 `leader-core`, 백엔드 고유 규칙은 각 백엔드 모듈
- **대상 파일**:
  - **신규 생성**: `leader-core/src/main/kotlin/io/bluetape4k/leader/LockNameValidator.kt`
  - **신규 생성**: `leader-core/src/test/kotlin/io/bluetape4k/leader/LockNameValidatorTest.kt`
  - **수정**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoLock.kt`
    (기존 `internal fun validateLockName()` → core 호출 wrapper로 변경, `:slot:` 검증 추가 유지)
- **구현 지침**:
  - `leader-core`에 `LockNameValidator.kt` 신규 생성:
    ```kotlin
    package io.bluetape4k.leader

    // 첫 문자 1자(영숫자) + 이후 0~254자(영숫자/언더스코어/하이픈/콜론) = 최대 255자
    // 콜론(:)은 허용 — 백엔드별 `:slot:` 등의 특수 패턴 검증은 각 백엔드 담당
    private val LOCK_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-:]{0,254}$")

    /**
     * lockName의 공통 최소 검증. 백엔드 고유 규칙(예: MongoDB의 `:slot:` 금지)은
     * 각 백엔드 모듈의 내부 검증 함수가 추가로 수행.
     */
    fun validateLockName(lockName: String) {
        require(lockName.isNotBlank()) { "lockName must not be blank" }
        require(lockName.length <= 255) { "lockName must not exceed 255 characters: length=${lockName.length}" }
        require(LOCK_NAME_PATTERN.matches(lockName)) {
            "lockName contains invalid characters. Allowed: [a-zA-Z0-9_\\-:], got: $lockName"
        }
    }
    ```
  - `leader-mongodb/lock/MongoLock.kt`의 기존 `internal fun validateLockName()` 수정:
    ```kotlin
    // 기존 독립 검증 → core 공통 검증 + MongoDB 고유 규칙으로 변경
    internal fun validateMongoLockName(lockName: String) {
        validateLockName(lockName)  // core 공통 검증 호출
        require(!lockName.contains(":slot:")) { "lockName must not contain ':slot:': $lockName" }
    }
    ```
  - `validateLockName`은 `public` (패키지 레벨 최상위 함수)
  - `LOCK_NAME_PATTERN`은 `private val` (파일 레벨)
  - KDoc 작성: 허용 문자, 길이 제한, 2-tier 설계 의도 포함
  - `LockNameValidatorTest.kt` 작성: 유효/무효 lockName 경계값 테스트
  - **Breaking Change 인지**: 기존에 `.`(점)이나 공백, 특수문자를 사용하던 lockName은 새 정규식에 의해 거부됨.
    이는 의도적인 변경이며 스펙에 명시되어 있음
- **완료 기준**:
  - [ ] `leader-core`에 `LockNameValidator.kt` 생성됨
  - [ ] 화이트리스트 정규식 `LOCK_NAME_PATTERN` 적용됨 (`:slot:` 검증 제외)
  - [ ] `leader-mongodb/lock/MongoLock.kt`에 `validateMongoLockName`이 core 호출 + `:slot:` 검증 수행
  - [ ] `LockNameValidatorTest.kt` 경계값 테스트 작성됨
  - [ ] `./gradlew :leader-core:compileKotlin` 클린
  - [ ] KDoc 작성됨
- **의존**: 없음

---

### T10: leader-mongodb import 경로 수정 + MongoLock.kt 업데이트 + 회귀 확인

- **complexity**: medium
- **[MEDIUM-2]**: MongoLock.kt 내부 함수 호출 교체 포함
- **대상 파일** (5개 파일):
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoLock.kt`
    — T9에서 `validateMongoLockName` 으로 이름 변경 확인 + core import 추가
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElection.kt`
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderGroupElection.kt`
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderElection.kt`
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderGroupElection.kt`
- **구현 지침**:
  - `MongoLock.kt` 내부: `validateLockName(lockName)` 호출부를 `validateMongoLockName(lockName)`으로 교체
  - 나머지 4개 파일에서 import 변경:
    ```diff
    - import io.bluetape4k.leader.mongodb.lock.validateLockName
    + import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
    ```
  - 함수 호출 코드도 `validateMongoLockName(lockName)` 으로 변경
  - **회귀 검증**: 기존 MongoDB 테스트에서 사용하는 lockName이 새 화이트리스트를 통과하는지 확인
    - 기존 테스트 lockName 예시: `"daily-report"`, `"batch-job"` 등 — 영숫자+하이픈이므로 통과 예상
  - `./gradlew :leader-mongodb:compileKotlin` 클린 확인
- **완료 기준**:
  - [ ] 4개 파일의 import + 호출부가 `validateMongoLockName`으로 변경됨
  - [ ] `MongoLock.kt`가 core의 `validateLockName` import + MongoDB 래퍼 구조로 업데이트됨
  - [ ] `./gradlew :leader-mongodb:compileKotlin` 에러 없음
  - [ ] 기존 MongoDB 테스트 lockName이 새 정규식에 통과됨 (수동 확인)
- **의존**: T9

---

### T11: AbstractExposedTableTest.kt 테스트 베이스 작성

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/AbstractExposedTableTest.kt`
- **구현 지침**:
  - 추상 클래스 작성:
    ```kotlin
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract class AbstractExposedTableTest {
        companion object : KLogging() {
            @JvmStatic
            fun enableDialects(): List<TestDB> = listOf(TestDB.H2, TestDB.POSTGRESQL, TestDB.MYSQL_V8)
        }
    }
    ```
  - `@TestInstance(Lifecycle.PER_CLASS)` 필수 (CLAUDE.md + junit-platform.properties 표준)
  - `companion object : KLogging()` — 로깅 지원
  - `@JvmStatic` on `enableDialects()` — JUnit 5 `@MethodSource` 필수 조건
  - `TestDB` import: `bluetape4k-exposed-jdbc-tests`에서 제공
  - 실제 DB 구동: `withTables(testDB, *tables) { ... }` 유틸리티가 Testcontainers 기동/정리 담당
  - **[HIGH-2] 경고**: 하위 클래스에서 자체 `companion object` 정의 금지.
    정의하면 `AbstractExposedTableTest`의 `companion object`가 shadow되어 `@MethodSource("enableDialects")`가
    하위 클래스의 companion을 탐색하지만 `enableDialects()`가 없어 `No factory method found` 예외 발생.
    → 하위 클래스는 `companion object` 없이 `@MethodSource("enableDialects")` 직접 참조만 사용.
- **완료 기준**:
  - [ ] `AbstractExposedTableTest` 추상 클래스 생성됨
  - [ ] `enableDialects()` 메서드가 H2, POSTGRESQL, MYSQL_V8 반환
  - [ ] `@JvmStatic` + `companion object : KLogging()` 올바르게 구성됨
  - [ ] KDoc에 하위 클래스의 `companion object` 정의 금지 주의사항 작성됨
- **의존**: T4, T16

---

### T12: LeaderLockTableTest.kt 테스트 작성

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockTableTest.kt`
- **구현 지침**:
  - `AbstractExposedTableTest` 상속
  - `@ParameterizedTest @MethodSource("enableDialects")` 패턴 사용
  - 테스트 케이스 6개:
    1. `테이블 생성 및 삭제가 성공한다` — `withTables(testDB, LeaderLockTable) { }` DDL 검증
    2. `락 레코드 삽입 및 조회가 성공한다` — INSERT → SELECT by PK (`lockName`)
    3. `동일 lockName 중복 삽입 시 예외가 발생한다` — PK 중복 → exception 확인
    4. `만료된 락을 갱신할 수 있다` — `WHERE locked_until < NOW()` UPDATE 검증
    5. `token 불일치 시 삭제되지 않는다` — DELETE WHERE token 조건 검증
    6. `timestamp 정밀도가 보존된다` — Instant 저장/조회 후 비교 (DB별 정밀도 차이 고려)
  - INSERT: `LeaderLockTable.insert { it[lockName] = ...; it[token] = UUID.randomUUID().toString(); ... }`
  - SELECT: `LeaderLockTable.selectAll().where { LeaderLockTable.lockName eq ... }`
  - Kluent 매처 사용: `shouldBeEqualTo`, `shouldNotBeNull` 등
  - timestamp 정밀도: H2(나노초), PostgreSQL(마이크로초), MySQL(마이크로초) — 밀리초 단위 비교로 통일
  - **[CRITICAL-2]**: 만료 row 삽입 시 반드시 `Instant.now().minusSeconds(60)` 이상 과거 시각 사용
- **완료 기준**:
  - [ ] 6개 테스트 케이스 작성됨
  - [ ] H2, PostgreSQL, MySQL 파라미터화 테스트로 동작
  - [ ] PK 중복 예외 검증됨
  - [ ] fencing token 기반 DELETE 조건 검증됨
- **의존**: T5, T11

---

### T13: LeaderGroupLockTableTest.kt 테스트 작성

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/tables/LeaderGroupLockTableTest.kt`
- **구현 지침**:
  - `AbstractExposedTableTest` 상속
  - 테스트 케이스 6개:
    1. `테이블 생성 및 삭제가 성공한다` — DDL 검증
    2. `복합 PK (lockName, slot) 삽입이 성공한다` — 동일 lockName, 다른 slot 삽입
    3. `동일 (lockName, slot) 중복 삽입 시 예외가 발생한다` — 복합 PK 중복 검증
    4. `활성 슬롯은 locked_until >= NOW() 조건으로만 카운트한다` — 만료된 row 제외 확인
    5. `만료된 슬롯은 신규 획득 가능하다` — `WHERE slot = ? AND locked_until < NOW()` UPDATE
    6. `특정 slot 범위 질의가 가능하다` — `WHERE slot BETWEEN 0 AND ?` SELECT
  - **[CRITICAL-2] 만료 row 오프셋 최소값**: `Instant.now().minusSeconds(60)` 이상 과거만 사용.
    작은 오프셋(1ms 미만)은 DB clock-to-JVM drift로 인해 flaky 테스트 유발.
  - **핵심**: 테스트 4번에서 `locked_until`을 과거 시각으로 설정한 row가 활성 카운트에서 제외되는지 검증
    ```kotlin
    // 만료된 슬롯 — 최소 60초 이전 사용 (clock drift 방지)
    LeaderGroupLockTable.insert {
        it[lockName] = "test-group"
        it[slot] = 0
        it[lockedUntil] = Instant.now().minusSeconds(60)  // 이미 만료 (60초 여유)
        ...
    }
    // 활성 슬롯 카운트 = 0 이어야 함
    ```
- **완료 기준**:
  - [ ] 6개 테스트 케이스 작성됨
  - [ ] 복합 PK 중복 예외 검증됨
  - [ ] 만료 슬롯이 활성 카운트에서 제외됨 (`locked_until >= NOW()` 조건)
  - [ ] slot 범위 질의 검증됨
- **의존**: T6, T11

---

### T14: LeaderLockHistoryTableTest.kt 테스트 작성

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockHistoryTableTest.kt`
- **구현 지침**:
  - `AbstractExposedTableTest` 상속
  - 테스트 케이스 6개:
    1. `테이블 생성 및 삭제가 성공한다` — DDL 검증
    2. `이력 레코드 삽입이 성공한다` — 각 status(`ACQUIRED`, `COMPLETED`, `FAILED`, `EXPIRED`)별 INSERT
    3. `id가 자동 증가한다` — 연속 INSERT 후 id 비교 (`id2 > id1`)
    4. `lockName + startedAt 인덱스를 활용한 조회가 성공한다` — 인덱스 기반 조회 검증
    5. `finishedAt, durationMs nullable 컬럼이 정상 동작한다` — ACQUIRED 상태에서 null 허용 확인
    6. `30일 이전 데이터 삭제가 성공한다` — `DELETE WHERE started_at < NOW() - 30일` 쿼리 검증
  - `HistoryStatus.ACQUIRED.name` 형태로 status 값 저장
  - slot은 그룹 락이면 `0`, 단일 리더 락이면 `null`
  - token은 `UUID.randomUUID().toString()`
  - locked_until은 `Instant.now().plusSeconds(60)` (ACQUIRED), `Instant.now().minusSeconds(60)` (EXPIRED 판정용)
  - **[CRITICAL-3] 30일 삭제 쿼리**: DB-native INTERVAL 대신 Kotlin Instant binding 사용.
    H2/PostgreSQL/MySQL의 INTERVAL 문법이 상이하므로 JVM 단에서 파라미터를 계산해 전달:
    ```kotlin
    val cutoff = Instant.now().minus(31, ChronoUnit.DAYS)
    LeaderLockHistoryTable.deleteWhere { startedAt less cutoff }
    ```
    `cutoff`는 Kotlin `Instant`; Exposed가 DB별 `timestamp` 파라미터 바인딩 처리함.
- **완료 기준**:
  - [ ] 6개 테스트 케이스 작성됨
  - [ ] AUTO_INCREMENT id 검증됨
  - [ ] nullable 컬럼(finishedAt, durationMs, slot) 검증됨
  - [ ] token, slot, locked_until 컬럼 DDL이 스펙과 일치
  - [ ] 30일 데이터 정리 쿼리 검증됨
- **의존**: T7, T11

---

### T15: ExposedLeaderSchemaTest.kt 스키마 헬퍼 테스트

- **complexity**: medium
- **대상 파일**: `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/ExposedLeaderSchemaTest.kt`
- **구현 지침**:
  - `AbstractExposedTableTest` 상속
  - 테스트 케이스 3개:
    1. `allTables에 3개 테이블이 포함되어 있다` — `ExposedLeaderSchema.allTables.size shouldBeEqualTo 3`
    2. `allTables로 SchemaUtils.createMissingTablesAndColumns 실행이 성공한다` — DDL 일괄 실행 검증
    3. `allTables로 SchemaUtils.drop 실행이 성공한다` — 테이블 일괄 삭제 검증
  - **[MEDIUM-3]**: Exposed 1.2.0에 `withDb`는 없음. 직접 `transaction(database) { ... }` 사용:
    ```kotlin
    // bluetape4k-exposed-jdbc-tests의 TestDB.connect() 로 Database 인스턴스 획득
    val db = testDB.connect()
    transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
    }
    // 테이블 존재 확인 후
    transaction(db) {
        SchemaUtils.drop(*ExposedLeaderSchema.allTables)
    }
    ```
  - `TestDB.connect()` 반환 타입 확인 — `bluetape4k-exposed-jdbc-tests` 소스에서 확인 후 구현
  - 대안: `withTables(testDB, *ExposedLeaderSchema.allTables) { ... }` 패턴도 가능 (withTables 내부가 트랜잭션 포함 시)
  - **[HIGH-3]** `allTables`는 `Array<Table>` 유지. `SchemaUtils.create(vararg tables: Table)` vararg를
    `*ExposedLeaderSchema.allTables` 스프레드로 직접 전달 가능 (List 변환 없이 O(1))
- **완료 기준**:
  - [ ] 3개 테스트 케이스 작성됨
  - [ ] `allTables` 배열로 일괄 create/drop 검증됨
  - [ ] H2, PostgreSQL, MySQL 모두 통과
- **의존**: T8, T11

---

### T16: junit-platform.properties 작성

- **complexity**: low
- **[HIGH-1] 실행 순서**: Group 0에 배치 — T11(AbstractExposedTableTest) 작성 전에 반드시 존재해야 함.
  JUnit 5는 `junit-platform.properties`를 클래스패스에서 읽어 `@TestInstance(PER_CLASS)` 상속 동작을 결정.
  T16이 T11보다 늦게 실행되면 테스트 실행 환경이 `PER_METHOD`(기본값)로 동작해 companion 팩토리 오류 가능.
- **대상 파일**: `leader-exposed-core/src/test/resources/junit-platform.properties`
- **구현 지침**:
  - 기존 `leader-mongodb` 모듈의 설정을 그대로 복사:
    ```properties
    junit.jupiter.extensions.autodetection.enabled=true
    junit.jupiter.testinstance.lifecycle.default=per_class

    junit.jupiter.execution.parallel.enabled=false
    junit.jupiter.execution.parallel.mode.default=same_thread
    junit.jupiter.execution.parallel.mode.classes.default=concurrent
    ```
  - MEMORY.md 표준: PER_CLASS + parallel=false
- **완료 기준**:
  - [ ] `junit-platform.properties` 생성됨
  - [ ] PER_CLASS + parallel=false 설정됨
- **의존**: 없음 (Group 0, T11 이전 완료 필수)

---

### T17: 전체 빌드 + 테스트 검증

- **complexity**: high
- **대상 파일**: 없음 (검증 태스크)
- **구현 지침**:
  - 순서대로 실행:
    1. `./gradlew :leader-exposed-core:compileKotlin` — 컴파일 에러 제로
    2. `./gradlew :leader-exposed-core:test` — 전체 테스트 통과 (H2 + PostgreSQL + MySQL)
    3. `./gradlew :leader-core:test` — core 모듈 회귀 없음
    4. `./gradlew detekt` — 린트 통과
  - 테스트 실패 시 원인 분석 후 관련 태스크로 돌아가 수정
  - DB별 TIMESTAMP 정밀도 차이로 인한 테스트 실패 가능 — 밀리초 단위 truncate 비교로 대응
- **완료 기준**:
  - [ ] `:leader-exposed-core:compileKotlin` 클린
  - [ ] `:leader-exposed-core:test` 전체 통과
  - [ ] `:leader-core:test` 회귀 없음
  - [ ] `detekt` 통과
- **의존**: T12, T13, T14, T15, T16

---

### T18: MongoDB 모듈 회귀 테스트

- **complexity**: medium
- **대상 파일**: 없음 (검증 태스크)
- **구현 지침**:
  - `./gradlew :leader-mongodb:test` 실행
  - `validateLockName` 이동 후:
    - import 경로 변경이 올바른지 확인
    - 새 화이트리스트 정규식으로 인해 기존 테스트 lockName이 거부되지 않는지 확인
  - 실패 시:
    - lockName에 새로 금지된 문자 포함 → 테스트 lockName 수정 (영숫자/하이픈/콜론만 사용)
    - import 경로 오류 → T10 재수정
- **완료 기준**:
  - [ ] `./gradlew :leader-mongodb:test` 전체 통과
  - [ ] `validateLockName` import 경로 정상
  - [ ] 기존 lockName이 새 정규식에 의해 거부되지 않음
- **의존**: T10, T17

---

### T19: README.md + README.ko.md 작성

- **complexity**: low
- **대상 파일**:
  - `leader-exposed-core/README.md`
  - `leader-exposed-core/README.ko.md`
- **구현 지침**:
  - 영문 README.md 포함 내용:
    - 모듈 역할 (스키마 정의 전용, JDBC/R2DBC 없음)
    - 테이블 3개 설명 (LeaderLockTable, LeaderGroupLockTable, LeaderLockHistoryTable)
    - JDBC/R2DBC 분리 전략 다이어그램
    - 사용 예시: `SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)`
    - Gradle 의존성 추가 방법
  - 한국어 README.ko.md: 동일 구조의 한국어 번역
  - 기존 `leader-mongodb/README.md` 스타일 참고
- **완료 기준**:
  - [ ] `README.md` 영문 작성됨
  - [ ] `README.ko.md` 한국어 작성됨
  - [ ] 모듈 역할, 테이블 설명, 사용 예시 포함
- **의존**: T17, T18

---

## 복잡도 요약

| 복잡도 | 태스크 | 개수 |
|--------|--------|------|
| **high** | T7 (HistoryTable), T9 (validateLockName 이동), T17 (전체 빌드 검증) | 3 |
| **medium** | T4 (build.gradle.kts), T5 (LockTable), T6 (GroupLockTable), T10 (MongoDB import), T11 (테스트 베이스), T12~T15 (테스트 4개), T18 (MongoDB 회귀) | 10 |
| **low** | T1 (toml), T2 (상수), T3 (enum), T8 (Schema), T16 (junit-platform), T19 (README) | 6 |

**총 태스크: 19개** (high 3 / medium 10 / low 6)

---

## 병렬 실행 가능 그룹

| 그룹 | 태스크 | 병렬 가능 | 예상 시간 |
|------|--------|-----------|-----------|
| 0 | T1, T2, T3, **T16** | 모두 병렬 | 5분 |
| 1 | T4 | 단독 | 5분 |
| 2 | T5, T6, T7 | 모두 병렬 | 10분 |
| 3 | T8 | 단독 | 3분 |
| 4 | T9, T10 | 순차 (T9→T10) | 10분 |
| 5 | T11 (T4, T16 완료 후) | 단독 | 5분 |
| 6 | T12, T13, T14, T15 | 모두 병렬 | 15분 |
| 7 | T17, T18 | T17→T18 순차 | 10분 |
| 8 | T19 | 단독 | 10분 |

> Group 0~3 (스키마 구현)과 Group 4 (validateLockName 2-tier)는 독립적으로 병렬 진행 가능.
> **T16은 Group 0에 배치** — T11 실행 전에 `junit-platform.properties`가 존재해야 함.
> Group 5 (테스트 베이스)는 T4 + T16 완료 후 즉시 시작 가능.

---

## 위험 요소 및 대응

| 위험 | 영향 | 대응 |
|------|------|------|
| `exposed-java-time`의 `timestamp()` API가 Exposed 1.2.0에서 변경됨 | T5, T6, T7 컴파일 실패 | Context7 또는 공식 docs에서 Exposed 1.2.0 API 확인 |
| `bluetape4k-exposed-jdbc-tests`의 `TestDB` enum에 `MYSQL_V8`이 없음 | T11~T15 테스트 실패 | `bluetape4k-exposed-jdbc-tests` 소스 확인, 대안 enum 값 탐색 |
| MySQL 8 Testcontainers에서 TIMESTAMP 정밀도 불일치 | T12 테스트 6번 실패 | 밀리초 단위 truncate 비교로 대응 |
| `validateLockName` 화이트리스트가 기존 MongoDB lockName 거부 | T18 회귀 테스트 실패 | 기존 테스트 lockName이 `[a-zA-Z0-9_\-:]` 범위인지 사전 확인 |
| `withTables` 유틸리티가 MySQL 8 지원하지 않음 | T12~T15 MySQL 테스트 실패 | `bluetape4k-exposed-jdbc-tests` 소스 확인 후 직접 DB 연결 코드 작성 |
