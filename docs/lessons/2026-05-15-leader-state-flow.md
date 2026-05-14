# Lessons Learned — leaderStateFlow() Extension (#225) (2026-05-15)

**관련 PR**: #225
**영향 모듈**: `leader-core`

## L1: `stateIn(scope = this)` in `runSuspendIO` causes 180s timeout for all tests

### 문제

`runSuspendIO { }` 내에서 `leaderStateFlow("my-lock", this)` 를 호출하면, `stateIn` 이 `this` (= `withTimeout` scope) 의 **child Job** 으로 컬렉션 코루틴을 launch 한다.
Structured concurrency 규칙에 따라 `withTimeout` scope 는 모든 child 가 완료될 때까지 종료되지 않는다.
`stateIn` 코루틴은 SharedFlow 에서 이벤트를 무한정 collect 하므로 절대 자연 종료되지 않는다.
→ 테스트 본문의 assertions 이 통과해도 `withTimeout` 이 180초 타임아웃을 기다린 뒤 `TimeoutCancellationException` 을 던진다.
→ **모든 9개 테스트가 3분 대기 후 실패** (27분 22초 전체 실행시간).

### 교훈

`runSuspendIO` (= `runBlocking { withTimeout { } }`) scope 에 수명이 테스트 본문보다 긴 코루틴을 launch 하면 안 된다.
`leaderStateFlow` / `stateIn` 처럼 **무기한 collect 하는 flow** 를 위한 scope 는 반드시 독립적인 `CoroutineScope(Dispatchers.IO + Job())` 으로 분리하고, 테스트 종료 시 `finally { scope.cancel() }` 로 명시적으로 취소해야 한다.

```kotlin
// WRONG — stateIn coroutine blocks withTimeout
runSuspendIO {
    val flow = publisher.leaderStateFlow("my-lock", this)
}

// CORRECT — independent scope, explicit cancel
runSuspendIO {
    val scope = CoroutineScope(Dispatchers.IO + Job())
    try {
        val flow = publisher.leaderStateFlow("my-lock", scope)
        // ...
    } finally {
        scope.cancel()
    }
}
```

---

## L2: `MutableSharedFlow(replay = 0)` drops events emitted before stateIn subscribes

### 문제

`stateIn(SharingStarted.Eagerly)` 는 launch 후 **비동기적으로** upstream 을 collect 한다.
`replay = 0` 인 `MutableSharedFlow` 에서 stateIn 코루틴이 구독 등록 전에 `emit()` 를 호출하면 이벤트가 **드롭**된다.
`yield()` 만으로는 IO thread pool 에서 구독 등록 완료를 보장할 수 없어 `flow.first { predicate }` 가 영원히 대기한다.

### 교훈

테스트용 `FakeEventPublisher` 는 `MutableSharedFlow(replay = 1, ...)` 을 사용해야 한다.
`replay = 1` 은 늦게 구독한 stateIn 코루틴도 마지막 이벤트를 수신할 수 있도록 보장한다.
추가로 `delay(50)` 으로 구독 타이밍을 확보하면 더 안전하다.

---

## L3: Gradle test result cache 손상 → `KryoException: Buffer underflow`

### 문제

테스트 프로세스를 외부에서 강제 종료(SIGTERM/SIGKILL)하면 Gradle 의 직렬화된 테스트 결과 파일이 불완전하게 기록된다.
이후 실행 시 `getPreviousFailedTestClasses` 에서 Kryo 역직렬화 시 `EOFException` 이 발생해 테스트가 실행조차 되지 않는다.

### 교훈

테스트가 반복적으로 `EOFException` / `KryoException` 으로 실패하면 아래 디렉터리를 삭제 후 재실행한다.

```bash
rm -rf leader-core/build/test-results \
       leader-core/build/tmp/test \
       leader-core/build/.gradle
```

---

## L4: Codex review with correct worktree directory is essential

### 문제

`codex exec review` 는 CWD 기준 git 상태를 검토한다.
Claude Code 세션의 기본 CWD 가 다른 worktree(`fix-173`)였기 때문에, 처음 5회의 `./gradlew :leader-core:test` 명령이 모두 잘못된 모듈에서 실행됐다.

### 교훈

- `./gradlew` 실행 시 `--project-dir <worktree-path>` 를 항상 명시한다.
- `codex exec review` 는 해당 worktree 의 CWD 에서 실행해야 한다 (`bash -c 'cd <worktree> && codex exec review ...'`).
- Claude Code 세션이 시작될 때 primary working directory 를 확인하고, 작업 worktree 와 다르면 즉시 절대 경로를 사용하도록 전환한다.
