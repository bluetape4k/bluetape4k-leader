# Lesson: 1_000_000L 매직 넘버를 TimeUnit.NANOSECONDS.toMillis()로 대체

**날짜**: 2026-05-16 **문제**: #265 **PR**: TBD

## 근본 원인

매직 넘버 `1_000_000L`(나노초-밀리초 제수)는 명명된 상수 없이 4개 모듈의 7개 파일에서 21번 나타나 의도를 불투명하게 만들고 유지 관리 위험을 초래했습니다.

## 결정

7개 파일 모두에서 `(System.nanoTime() - acquiredAtNanos) / 1_000_000L`를 `TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)`로 대체했습니다.

다음과 같은 이유로 명명된 상수(`NANOS_PER_MS`) 대신 `TimeUnit.NANOSECONDS.toMillis()`를 선택했습니다.
- 자체 문서화 — 상수가 무엇을 의미하는지 찾아볼 필요가 없습니다.
- 표준 JDK 관용구(java.util.concurrent.TimeUnit)
- 모듈 간에 공유할 새로운 상수가 없습니다.
- 모듈 간 가시성 문제 방지(내부 및 공개)

변경되지 않음: `LettuceLock.kt`, `LettuceSemaphore.kt`, `LettuceSlotTokenGroup.kt`의 `RETRY_DELAY_NANOS` 및 `SPIN_DELAY_NANOS` 정의 — 명명된 상수 정의의 일부로 `1_000_000L`를 곱합니다(이미 읽을 수 있음).

## 변경된 파일

4개 모듈에 걸쳐 7개 프로덕션 파일:
- `leader-exposed-jdbc`: `ExposedJdbcLeaderElector.kt` (4), `ExposedJdbcLeaderGroupElector.kt` (4)
- `leader-exposed-r2dbc`: `ExposedR2DbcSuspendLeaderElector.kt` (2)
- `leader-mongodb`: `MongoLeaderElector.kt` (4), `MongoSuspendLeaderElector.kt` (2)
- `leader-redis-lettuce`: `LettuceLeaderElector.kt` (4), `LettuceSuspendLeaderElector.kt` (2)

## 검증

- `rg "/ 1_000_000L" --glob "*.kt"` → 프로덕션 코드에서 0 일치
- `./gradlew :leader-exposed-jdbc:build :leader-exposed-r2dbc:build :leader-mongodb:build :leader-redis-lettuce:build -x test` → 빌드 success

## 향후 지침

`System.nanoTime()`에서 새로운 기간을 밀리초로 변환하려면 `/ 1_000_000L`가 아닌 `TimeUnit.NANOSECONDS.toMillis(nanos)`를 사용하세요.
