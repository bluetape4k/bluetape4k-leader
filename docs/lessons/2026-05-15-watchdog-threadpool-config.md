# Lessons Learned — watchdog thread pool config + async extend + concurrent tests (2026-05-15)

**관련 PR**: #246
**영향 모듈**: `leader-core`, `leader-spring-boot`

## L1: `ScheduledThreadPoolExecutor.setCorePoolSize()` live 적용 — restart 불필요

### 문제
`configure(watchdogThreads = N)` 후 `restart()`가 no-op(이미 실행 중)이어서 thread count가 반영되지 않음.
Spring Boot YAML `bluetape4k.leader.watchdog-threads: 4` 설정이 무시되는 silent bug.

### 교훈
`ScheduledThreadPoolExecutor.setCorePoolSize(n)` 는 실행 중인 executor에 즉시 적용됨.
`configure()`에서 `scheduler.corePoolSize = watchdogThreads`를 직접 호출하면 scheduler restart 없이 thread count 변경 가능.
**"takes effect on next restart()" 패턴은 `ScheduledThreadPoolExecutor`에 불필요.**

---

## L2: Kotlin `object` 프로퍼티 초기화 순서 — 의존성 있는 프로퍼티는 위에서 아래로

### 문제
`private const val DEFAULT_WATCHDOG_THREADS = 2`가 object 하단(line 283)에 선언되어 있었고,
`configuredThreadCount = DEFAULT_WATCHDOG_THREADS` + `scheduler = newScheduler()`를 추가할 때
초기화 순서 문제 발생 가능성이 있었음.

### 교훈
Kotlin `object` 초기화는 선언 순서대로 실행됨. `const val`은 compile-time constant라 순서 무관하지만,
`internal val`로 변경 시 `DEFAULT_WATCHDOG_THREADS` → `configuredThreadCount` → `scheduler` 순서로
재배치 필요. 의존 관계 있는 프로퍼티는 상단부터 선언하는 것이 원칙.

---

## L3: `internal val`은 다른 모듈에서 접근 불가 — cross-module API는 `public` 또는 별도 accessor

### 문제
`internal val DEFAULT_WATCHDOG_THREADS`를 `leader-spring-boot` 모듈에서 참조하면
`Cannot access 'val DEFAULT_WATCHDOG_THREADS': it is internal` 컴파일 에러 발생.

### 교훈
`internal`은 같은 Gradle 모듈 내에서만 가시. cross-module 접근이 필요하면:
- `public val`로 선언, 또는
- `watchdogThreadCount()` 같은 public 메서드로 노출.
이 케이스에서는 `watchdogThreadCount()`를 fallback으로 사용하여 해결.

---

## L4: async extend에 virtual thread 선택 이유

### 문제
slow backend extend 호출이 single-thread scheduler를 block → 100개 watchdog 중 연장이 지연됨.

### 교훈
`Thread.ofVirtual()`이 `CompletableFuture.runAsync()`보다 적합한 경우:
- CI 2-core 환경에서 ForkJoinPool은 thread 1개 → 동시 dispatch 불가
- `CompletableFuture`는 ForkJoinPool에 의존, pool lifecycle 관리 별도 필요
- Virtual thread는 per-task 비용이 낮고 pool 없음 → N개 동시 dispatch에 최적
`extendInFlight: AtomicBoolean` guard로 같은 watchdog이 overlapping extend 하지 않도록 보호.

---

## L5: `asyncExtendEnabled` — tick 시점이 아닌 `start()` 시점에 캡처

### 문제
tick 시점에 `asyncExtendEnabled`를 읽으면, 실행 중 `configure()` 호출로 async mode가 바뀔 때
`extendInFlight` 없이 virtual thread dispatch 시도 가능.

### 교훈
`start()` 호출 시점에 `val capturedAsyncExtend = asyncExtendEnabled` + `val extendInFlight = if (capturedAsyncExtend) AtomicBoolean(false) else null`으로 캡처.
실행 중 `configure()` 변경은 이미 시작된 watchdog에 영향 없음 (새 `start()` 호출부터 적용).
이 패턴은 코루틴의 `CoroutineContext` 캡처와 동일한 원칙.

---

## L7: Virtual Thread 환경에서 `synchronized` 절대 금지 — `ReentrantLock` 필수

### 문제
`LeaderLeaseAutoExtenderLifecycle`이 `synchronized(lifecycleLock)` + `Any()` 모니터 락을 사용.
이 클래스는 virtual thread aware 모듈(`leader-spring-boot`)에 존재하며,
virtual thread 위에서 실행 시 carrier thread pinning → 성능 저하 + deadlock 위험.

### 교훈
**동기 코드 전체에 적용되는 절대 규칙:**
`synchronized` / `@Synchronized`는 virtual thread 코드에서 사용 금지.
반드시 `ReentrantLock()` + `lock.withLock { }` 대체.
bluetape4k-leader는 virtual thread aware 모듈을 포함하므로 모든 락 코드에 적용.

```kotlin
// WRONG
private val lock = Any()
synchronized(lock) { ... }

// CORRECT
private val lock = ReentrantLock()
lock.withLock { ... }
```

---

## L6: `MultithreadingTester` / `SuspendedJobTester` contract test 패턴

### 문제
기존 contract test는 단일 스레드 시나리오만 검증. 실제 concurrent 접근 시 mutex 보장 미검증.

### 교훈
Backend-agnostic contract base class에 `maxConcurrent == 1` 검증 추가:
```kotlin
MultithreadingTester().workers(8).rounds(4).add {
    elector.runIfLeader(lockName) {
        val n = currentHolders.incrementAndGet()
        maxConcurrent.getAndUpdate { max(it, n) }
        Thread.sleep(5)
        currentHolders.decrementAndGet()
    }
}.run()
maxConcurrent.get() shouldBeEqualTo 1
```
모든 backend 구현체가 상속 시 자동으로 동시성 mutex 계약을 검증하게 됨.
`AtomicInteger.getAndUpdate { max(it, n) }` 패턴으로 thread-safe max tracking.
