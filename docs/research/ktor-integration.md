# Ktor 통합 자료 조사

> 작성: 2026-04-30  
> 목적: `leader-ktor` 모듈 (옵션 C) 타당성 검토

---

## 1. 결론 요약

`leader-core`는 이미 framework-agnostic + coroutine-first이므로 Ktor에서 **직접 사용 가능**하다.  
`leader-ktor` 모듈의 부가가치는 **Ktor 전용 편의 계층** 세 가지다:

1. `install(LeaderElection) { ... }` 플러그인 DSL — 설정 통일
2. `ApplicationStarted/Stopped` lifecycle 연동 — graceful shutdown
3. `application.leaderScheduled(period, lockName) { ... }` — `@Scheduled` 부재 보완

---

## 2. Ktor 버전

| 버전 | 비고 |
|------|------|
| 2.x  | Feature → Plugin 리네임 (2.0), 현재 유지보수 |
| 3.0  | `monitor`, `parentCoroutineContext`, `rootPath` 소유권이 `ApplicationEnvironment` → `Application`으로 이동. `embeddedServer` API 변경 |
| 3.2  | 빌트인 DI (`ktor-server-di`) 추가. `dependencies { provide<T> { ... } }` DSL |

**권장**: Ktor **3.x** 타겟. 현재 프로젝트(Kotlin 2.3.21, coroutines 1.10.2) 완전 호환.

최신 Ktor 버전 확인: https://github.com/ktorio/ktor/releases

---

## 3. Plugin API (`createApplicationPlugin`)

Ktor 플러그인은 `createApplicationPlugin` DSL로 생성하고 `install()`로 설치한다.

```kotlin
// 플러그인 정의
val LeaderElectionPlugin = createApplicationPlugin(
    name = "LeaderElection",
    createConfiguration = ::LeaderElectionPluginConfig
) {
    val config = pluginConfig
    // lifecycle 구독
    on(MonitoringEvent(ApplicationStarted)) { application ->
        // 백그라운드 잡 시작
    }
    on(MonitoringEvent(ApplicationStopped)) {
        // 리소스 정리
    }
}

// 설치
fun Application.module() {
    install(LeaderElectionPlugin) {
        leaderElection = RedissonSuspendLeaderElection(redissonClient)
    }
}
```

**Ktor 3.0 주의**: `environment.monitor` → `application.monitor`로 이동.  
플러그인 내부에서 직접 `application.monitor.subscribe(ApplicationStarted) { ... }` 사용 가능.

---

## 4. Application CoroutineScope — 백그라운드 잡

`Application`은 `CoroutineScope`를 구현한다. `ApplicationStarted` 이후 `application.launch { ... }`로 백그라운드 코루틴 시작 가능.

```kotlin
// ApplicationStarted 이벤트에서 주기적 리더 전용 잡 실행
application.monitor.subscribe(ApplicationStarted) { app ->
    app.launch {
        while (isActive) {
            leaderElection.runIfLeader("periodic-job") {
                doWork()
            }
            delay(period)
        }
    }
}

// ApplicationStopped에서 자동 취소됨 (Application CoroutineScope 소멸)
```

`Application` 코루틴 스코프가 `ApplicationStopped`와 함께 취소되므로 별도 Job 추적 불필요.

---

## 5. 스케줄링 헬퍼 — `leaderScheduled`

Spring의 `@Scheduled`에 해당하는 Ktor 전용 확장 함수. **이것이 옵션 C의 핵심 부가가치.**

```kotlin
// 제안하는 API 형태
fun Application.leaderScheduled(
    lockName: String,
    period: Duration,
    leaderElection: SuspendLeaderElection,
    action: suspend () -> Unit,
): Job {
    return launch {
        while (isActive) {
            leaderElection.runIfLeader(lockName) { action() }
            delay(period)
        }
    }
}

// 사용 예
fun Application.module() {
    install(LeaderElectionPlugin) {
        leaderElection = redissonSuspendLeaderElection
    }
    leaderScheduled("daily-report", period = 1.hours) {
        reportService.generate()
    }
}
```

---

## 6. Ktor 빌트인 DI (3.2+)

Ktor 3.2.0부터 `ktor-server-di` artifact로 빌트인 DI 지원.

```kotlin
dependencies {
    provide<SuspendLeaderElection> { RedissonSuspendLeaderElection(redissonClient) }
}

// 모듈 파라미터로 주입
fun Application.module(leaderElection: SuspendLeaderElection) {
    install(LeaderElectionPlugin) {
        this.leaderElection = leaderElection
    }
}
```

`leader-ktor` 모듈이 직접 Koin에 의존할 필요 없이, Ktor 빌트인 DI 또는 수동 주입 모두 지원 가능.

---

## 7. 모듈 설계안

### 7.1 모듈 구조

```
leader-ktor/
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/leader/ktor/
    LeaderElectionPlugin.kt         — createApplicationPlugin DSL
    LeaderElectionPluginConfig.kt   — 설정 클래스
    ApplicationExt.kt               — leaderScheduled() 확장 함수
```

### 7.2 의존성

```kotlin
// leader-ktor/build.gradle.kts
dependencies {
    api(project(":leader-core"))
    compileOnly("io.ktor:ktor-server-core:$ktorVersion")  // Ktor 3.x
    
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio:$ktorVersion")
    testImplementation(project(":leader-redis-redisson"))
    testImplementation(Libs.testcontainers)
}
```

### 7.3 플러그인 설정 클래스

```kotlin
class LeaderElectionPluginConfig {
    var leaderElection: SuspendLeaderElection? = null
    // 필요 시 LeaderGroupElection도 추가
}
```

### 7.4 `ApplicationPlugin` vs `RouteScopedPlugin`

- `createApplicationPlugin` — 앱 전역 리더 선출. **이것만 구현.**
- `createRouteScopedPlugin` — 라우트별 리더 선출. 현재 필요성 낮음, 백로그.

---

## 8. 기술적 제약

| 항목 | 내용 |
|------|------|
| Ktor 최소 버전 | 3.0.0 (monitor 소유권 변경) |
| 코루틴 의존성 | `leader-core`가 이미 포함, 추가 불필요 |
| Thread safety | `Application.launch`는 코루틴 기반, `@Synchronized` 불필요 |
| graceful shutdown | `Application` CoroutineScope 소멸 시 자동 취소 |
| 테스트 | `testApplication { }` DSL로 단위 테스트 가능 |

---

## 9. 구현 선행 조건

- [ ] Ktor 최신 버전 확인 및 `Libs.kt`에 버전 상수 추가
- [ ] `leader-spring-boot3` 구현 형태 결정 (대칭성 확보)
- [ ] `SuspendLeaderElection` 인터페이스 Ktor 플러그인에 노출할 방식 확정
- [ ] `leaderScheduled` API 시그니처 확정 (DSL vs 파라미터)

---

## 10. 참고 링크

- [Ktor Custom Server Plugins](https://ktor.io/docs/server-custom-plugins.html)
- [Ktor Application Events](https://ktor.io/docs/server-events.html)
- [Ktor Migrating to 3.0](https://ktor.io/docs/migrating-3.html)
- [Ktor 3.2 DI](https://ktor.io/docs/whats-new-320.html)
- [ktorio/ktor GitHub](https://github.com/ktorio/ktor)
