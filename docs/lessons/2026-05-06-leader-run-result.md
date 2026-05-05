# Lessons Learned — LeaderRunResult sealed interface (2026-05-06)

**관련 PR**: #85 (feat/85-leader-run-result)
**영향 모듈**: leader-core, leader-spring-boot

## L1: MockK relaxed mock은 interface default method를 우회한다

### 문제
`LeaderElector`에 `runIfLeaderResult` default 메서드를 추가한 후, `runIfLeader`를 mocking하는 기존 테스트가 모두 실패했다. MockK의 relaxed mock은 interface default method에 대해 자동으로 stub을 생성하며, 실제 default 구현(runIfLeader 호출)을 실행하지 않는다. 따라서 `runIfLeader`를 mocking해도 `runIfLeaderResult`가 호출되면 그 stub이 무시된다.

### 교훈
**MockK relaxed mock + interface default method 패턴**: mock되는 메서드가 실제 코드에서 호출되는 메서드인지 확인해야 한다. Aspect가 `runIfLeaderResult`를 호출하면 테스트도 반드시 `runIfLeaderResult`를 stub해야 한다. 구현 메서드(default impl 내부의 `runIfLeader`)를 stub하면 우회된다.

---

## L2: `elected` 플래그 클로저 패턴으로 기존 backend 수정 없이 null 모호성 해결

### 문제
`runIfLeader()`는 (a) lock 미획득과 (b) action()의 정상 null 반환 두 경우를 모두 `null`로 반환해서 metrics가 잘못된 CONTENTION을 기록했다.

### 교훈
기존 backend(`LocalLeaderElector`, `RedissonLeaderElector`, etc.) 수정 없이 interface default method에서 `elected` flag closure 패턴으로 해결 가능하다:

```kotlin
fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> {
    var elected = false
    val value = runIfLeader(lockName) { elected = true; action() }
    return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
}
```

이 패턴은 단일 호출자 컨텍스트에서 스레드 안전하며, 모든 기존 구현체에 자동으로 적용된다.

---

## L3: `-jvm-default=enable`은 기존 프로젝트 일관성 유지이며 변경 불필요

### 문제
advisor가 `-jvm-default=enable`이 deprecated이고 published library의 Java ABI에 영향을 미칠 수 있다고 경고했다.

### 교훈
프로젝트 전체에서 `@JvmDefault`를 사용하지 않고 `-jvm-default=enable`을 사용하므로, 모든 interface default method는 `DefaultImpls` 패턴으로 컴파일된다. 기존 코드가 일관되게 동작하고 테스트가 통과하므로 변경 불필요. 새 메서드도 동일 패턴을 따른다. ABI 변경이 필요한 시점(예: `all-compatibility` 모드 전환)은 별도 이슈로 처리.

---

## L4: BodyThrownMarker vs backend 예외 구분은 `runIfLeaderResult`와도 올바르게 동작

### 문제
`LeaderElectionAspect`의 `BodyThrownMarker` 패턴이 새 `runIfLeaderResult` default method와 올바르게 상호작용하는지 검증이 필요했다.

### 교훈
`runIfLeaderResult`는 `runIfLeader`를 내부에서 호출하고, `executeBody`에서 던진 `BodyThrownMarker`는 backend(`runIfLeader` 구현)를 통해 그대로 전파된다. 모든 backend 구현이 `try { return action() } finally { ... }` 또는 동등 패턴을 사용하므로 marker가 재포장되지 않는다.
