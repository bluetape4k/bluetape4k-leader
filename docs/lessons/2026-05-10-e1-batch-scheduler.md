# Lessons Learned — E1 Batch Scheduler Example (2026-05-10)

**관련 PR**: TBD (feat/examples-batch-scheduler → develop)
**관련 Issue**: #154 (Epic #36)
**영향 모듈**: `examples/batch-scheduler/`, root `build.gradle.kts`, `settings.gradle.kts`, `.github/workflows/{ci,nightly}.yml`

## L1: bluetape4k KLogging — lambda 형태 호출은 별도 extension import 필요

### 문제
`KLogging` companion 의 `log` 객체는 SLF4J 호환 메서드(`info(String)`)만 노출.
`log.info { "..." }` (lambda) 호출은 `io.bluetape4k.logging.info`/`debug`/`warn` extension function 을 별도 import 해야 컴파일됨.

### 교훈
신규 모듈에서 KLogging companion 사용 시 lambda-style logging extension import 누락하지 말 것:

```kotlin
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info       // ✅ lambda overload
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
```

E2~E5 모두 동일 패턴 적용.

---

## L2: `executor.submit { lambda }` Kotlin 추론 — Future<*> vs Future<T?>

### 문제
`Executors.newFixedThreadPool(N).submit { lambda }` — Kotlin이 lambda 마지막 표현식의 반환 타입에 따라 `Callable<T>` vs `Runnable` overload 선택.
runnable로 추론되면 `Future.get()` 결과는 항상 `null`. 이를 `filterNotNull()` 검증에 쓰면 false negative.

### 교훈
multi-instance race 테스트에서 결과 검증은 Future 반환값에 의존하지 말고 `AtomicInteger` 카운터로 lambda 내부에서 직접 누적할 것:

```kotlin
val successCount = AtomicInteger(0)
executor.submit {
    val result = scheduler.run { ... }
    if (result != null) successCount.incrementAndGet()
}
```

E2~E5 동시 실행 검증에 동일 패턴 권장.

---

## L3: 다중 인스턴스 동시 실행 테스트 — `Thread.sleep` 의존 race 회피

### 문제
초기 테스트는 리더 job 안에서 `Thread.sleep(N)` 으로 패자들의 `tryLock(waitTime=M)` 만료를 기다림.
빠른 머신/CI 변동성에서 실패 가능. 시간 기반 race condition.

### 교훈
`CountDownLatch(loserCount)` 로 명시적 동기화:

```kotlin
val losersFinished = CountDownLatch(N - 1)

scheduler.run {
    losersFinished.await(timeout, SECONDS)  // 패자 완료까지 대기
    // ... do work
}
// 패자 경로
if (result == null) losersFinished.countDown()
```

E2~E5 의 contention 테스트는 모두 latch 기반으로 작성.

---

## L4: examples 모듈은 root `build.gradle.kts` 에서 publishing/signing/kover/nmcp 제외 필수

### 문제
bluetape4k-leader 의 `subprojects {}` 블록은 모든 모듈에 자동으로 `maven-publish`, `signing`, `nmcp`, kover aggregation 적용.
examples 는 외부 배포 대상이 아님. 그대로 두면 Maven Central 에 example artifacts 가 published 됨.

### 교훈
`examples/` 경로 기반 일괄 제외 패턴:

```kotlin
val isExample = path.startsWith(":examples:")

subprojects {
    if (isExample) {
        // skip publishing/signing setup
    }
    // ... rest
}

dependencies {
    subprojects
        .filter { !it.path.startsWith(":examples:") }
        .forEach { add("nmcpAggregation", project(it.path)) }
}
```

E2~E5 추가 시 root build 수정 불필요 — 자동 제외.

---

## L5: examples 모듈 README 의 `:run` 명령은 `application` 플러그인 필요

### 문제
README 에 `./gradlew :examples:batch-scheduler:run` 안내했으나 `application` 플러그인 미적용 시 `Task 'run' not found` 발생.

### 교훈
examples 모듈 build.gradle.kts 에 항상:

```kotlin
plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.<pkg>.<DemoObject>")
}
```

`mainClass` 는 정확한 FQN. Demo 는 `object: KLogging()` + `@JvmStatic fun main(args: Array<String>)` 패턴 사용 (top-level `fun main()` 보다 logger 사용 일관성 좋음).

---

## L6: CI/CD — examples 모듈은 별도 job 분리, paths-filter 트리거 추가

### 문제
nightly.yml + ci.yml 의 `coverage-report`, `ci-status`, `nightly-status` 가 모든 test job 의 needs 배열을 명시 — 새 examples job 누락 시 status check 가 통과해도 실제 실행 안 됨.

### 교훈
새 examples 모듈 추가 시 체크리스트:

1. `nightly.yml`: 새 `test-examples-<name>` job + `nightly-status.needs` 추가
2. `ci.yml`:
   - `changes` job 의 `outputs` + `paths-filter` 에 모듈 등록
   - 새 `test-examples-<name>` job + `coverage-report.needs` + `ci-status.needs` 추가
   - paths-filter 에는 examples 자체 + 의존하는 leader-* 모듈 모두 등록 (의존성 변경 시 examples 도 재테스트)

E2~E5 각각 4곳 (ci.yml outputs/filter + ci.yml job + nightly.yml job + nightly-status needs) 업데이트.

---

## L7: 워크스페이스 CLAUDE.md `assertFailsWith` 표준 패턴

### 문제
초기 테스트에서 `try { ... } catch (_: T) { }` 빈 catch 로 예외 검증 — silent swallow 룰 위반 + 표준 패턴 미사용.

### 교훈
bluetape4k 표준은 `io.bluetape4k.assertions.assertFailsWith<T> { ... }`.
- JUnit `assertThrows`, `kotlin.test.assertFailsWith`, `invoking { } shouldThrow` 모두 금지.
- suspend 함수 검증은 `coInvoking { } shouldThrow T::class`.

```kotlin
import io.bluetape4k.assertions.assertFailsWith

assertFailsWith<IllegalStateException> {
    s1.run<Unit> { throw IllegalStateException("...") }
}
```
