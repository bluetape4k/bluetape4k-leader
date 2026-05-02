# Lessons Learned — leader-exposed-core (#23) (2026-05-02)

**관련 PR**: #23
**영향 모듈**: `leader-core`, `leader-exposed-core`, `leader-mongodb`

## L1: Exposed 1.2.0에서 SqlExpressionBuilder는 DeprecationLevel.ERROR

### 문제
`import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq` 등 SqlExpressionBuilder 멤버 임포트가 컴파일 오류로 처리됨. 또한 `and`는 SqlExpressionBuilder에 존재하지 않아 "Unresolved reference" 발생.

### 교훈
Exposed 1.2.0부터 모든 SQL 연산자(`eq`, `and`, `less`, `greaterEq` 등)는 `org.jetbrains.exposed.v1.core` 패키지의 top-level 함수. 다음 import 패턴 사용:
```kotlin
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.greaterEq
```
또는 `import org.jetbrains.exposed.v1.core.*`

---

## L2: Table.exists()는 exposed-jdbc 전용 extension function

### 문제
`LeaderLockTable.exists()` 호출 시 "Unresolved reference 'exists'" 컴파일 오류.

### 교훈
`Table.exists()`는 `org.jetbrains.exposed.v1.jdbc` 패키지에 있음. 명시적 import 필요:
```kotlin
import org.jetbrains.exposed.v1.jdbc.exists
```

---

## L3: testcontainers-mysql의 artifact ID는 testcontainers-mysql (표준 mysql이 아님)

### 문제
`org.testcontainers:mysql:2.0.4`로 지정했더니 resolution 실패. 이 프로젝트는 bluetape4k 커스텀 testcontainers 생태계(버전 2.0.x)를 사용하며, 모든 DB 모듈이 `testcontainers-` 접두어를 가짐.

### 교훈
이 프로젝트의 testcontainers 의존성:
- `org.testcontainers:testcontainers-postgresql` (표준은 `postgresql`)
- `org.testcontainers:testcontainers-mongodb` (표준은 `mongodb`)
- `org.testcontainers:testcontainers-mysql` (표준은 `mysql`)

BOM: `org.testcontainers:testcontainers-bom:2.0.x`

---

## L4: JVM 메서드 이름에 >=, (), > 문자 불가

### 문제
백틱 테스트 이름에 `locked_until >= NOW()` 포함 시 컴파일 오류: "Name contains illegal characters: >".

### 교훈
JVM 메서드 이름에 `>`, `<`, `(`, `)` 등 허용 불가. 백틱 이름에서도 연산자 기호 제거 필요. 예: `>= NOW()` → `이상인`.

---

## L5: @ValueSource에는 컴파일 타임 상수만 허용

### 문제
`@ValueSource(strings = ["a".repeat(255)])` 형태 사용 시 "Annotation argument must be a compile-time constant" 오류.

### 교훈
경계값 테스트(예: 255자 문자열)는 별도 `@Test` 메서드로 작성해야 함. `@ValueSource`/`@CsvSource`에는 리터럴 문자열만 사용 가능.

---

## L6: Gradle 빌드를 항상 worktree 디렉토리에서 실행

### 문제
메인 repo 루트(`/Users/debop/.../bluetape4k-leader`)에서 `./gradlew :leader-exposed-core:test` 실행 시 `NO-SOURCE` — 메인 브랜치에 테스트 파일 없음.

### 교훈
Gradle을 worktree 디렉토리에서 실행해야 해당 브랜치의 소스 파일을 빌드함:
```bash
cd .worktrees/feat/issue-23-exposed-core && ./gradlew :leader-exposed-core:test
```
