# 단원: 동시성 테스트를 MultithreadingTester / SuspendedJobTester로 마이그레이션

**날짜**: 2026-05-16 **문제**: #275 **PR**: #282 **영향을 받는 모듈**: `leader-core`

## 요약

`LocalSuspendLeaderGroupElectorTest.kt`에 이미 설정된 패턴과 일치하여 원시 `Executors.newFixedThreadPool` + `CountDownLatch`에서 `coroutineScope` + `async(Dispatchers.IO)` + `AtomicInteger` 폴링으로 `LeaderGroupElectionStateTest.kt`를 마이그레이션했습니다.

## 범위

초기 감사에서는 이미 `MultithreadingTester` 또는 `SuspendedJobTester`를 사용하고 있는 33개 이상의 테스트 파일이 발견되었습니다. 유일하게 남아 있는 원시 실행기 패턴은 `LeaderGroupElectionStateTest.kt`의 단일 테스트였습니다. `AsyncLeaderElectorContractTest.kt` 및 `AsyncLeaderGroupElectorContractTest.kt`에는 마이그레이션할 원시 스레드 기본 요소가 없었습니다.

## 마이그레이션 패턴

**이전**(원시 스레드 풀):
```kotlin
val startLatch = CountDownLatch(2)
val holdLatch = CountDownLatch(1)
val executor = Executors.newFixedThreadPool(2)

repeat(2) {
    executor.submit {
        election.runIfLeader(lockName) {
            startLatch.countDown()
            holdLatch.await()
        }
    }
}
startLatch.await(2, TimeUnit.SECONDS)
// check state ...
holdLatch.countDown()
executor.shutdown()
executor.awaitTermination(3, TimeUnit.SECONDS)
```

**이후**(코루틴 + AtomicInteger 폴링):
```kotlin
val acquiredCount = AtomicInteger(0)
val holdLatch = CountDownLatch(1)  // required: action lambda is blocking, not suspend

coroutineScope {
    val jobs = List(2) {
        async(Dispatchers.IO) {
            election.runIfLeader(lockName) {
                acquiredCount.incrementAndGet()
                holdLatch.await()
            }
        }
    }
    while (acquiredCount.get() < 2) { delay(5.milliseconds) }
    // check state ...
    holdLatch.countDown()
    jobs.awaitAll()
}
```

## CountDownLatch가 유지되는 이유

`LocalLeaderGroupElector.runIfLeader`는 일시 중단 람다가 아닌 `() -> T` 차단 람다를 사용합니다. `CountDownLatch.await()`는 차단 작업 내에서 잠금을 유지하는 올바른 메커니즘입니다. 이는 `LocalLeaderGroupElectionTest.kt`의 기존 패턴과 일치합니다.

주요 개선 사항은 `Executors.newFixedThreadPool`(비구조화, 누출 가능성 있음)를 구조화된 `coroutineScope { async(Dispatchers.IO) { } }`로 교체하는 것입니다.

## 향후 지침

- `MultithreadingTester` / `SuspendedJobTester`: 스트레스/실행 및 전체 동시성 테스트에 사용
- `coroutineScope + async(Dispatchers.IO)` + `AtomicInteger` 폴링: 차단 선택기를 사용하여 "보류 중 검증" 정확성 테스트에 사용
- 차단 작업 람다 내부의 `CountDownLatch.await()`는 허용되며 예상됩니다.
- 테스트 중 `Executors.newFixedThreadPool`: 항상 코루틴(구조적) 또는 `MultithreadingTester`로 교체
