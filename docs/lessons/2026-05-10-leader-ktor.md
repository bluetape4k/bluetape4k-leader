# Lessons Learned — leader-ktor 통합 모듈 (2026-05-10)

**관련 PR**: TBD
**관련 Issue**: #37
**영향 모듈**: `leader-ktor/`, `leader-bom/`, `gradle/libs.versions.toml`, `.github/workflows/{ci,nightly}.yml`, `settings.gradle.kts`

## L1: Ktor `createApplicationPlugin` 의 `pluginConfig` 노출 한계

### 문제
초기 spec 은 `Application.leaderScheduled(... plugin(LeaderElectionPlugin).pluginConfig.leaderElection!!)` 시그니처로 plugin config 직접 접근을 가정. 하지만 Ktor 의 `PluginInstance.builder` 가 `internal` — `pluginConfig` 는 `createApplicationPlugin { }` 블록 안에서만 접근 가능, 외부 호출자가 사용 불가.

### 교훈
**Application.attributes** 패턴 사용:

```kotlin
private val LeaderElectionConfigKey = AttributeKey<LeaderElectionPluginConfig>("leader-election-config")

val LeaderElectionPlugin = createApplicationPlugin(...) {
    val config = pluginConfig
    requireNotNull(config.leaderElection) { "..." }
    application.attributes.put(LeaderElectionConfigKey, config)
    // ...
}

fun Application.leaderElectionPluginConfig(): LeaderElectionPluginConfig =
    attributes.getOrNull(LeaderElectionConfigKey)
        ?: error("LeaderElectionPlugin 미설치")
```

다른 Ktor plugin 작성 시 동일 패턴 적용 (외부 노출 필요한 설정).

---

## L2: `!!` 금지 (워크스페이스 규칙) — `requireNotNull` + `error()`

### 교훈
Ktor 예제 코드에 `!!` 흔히 등장하지만 워크스페이스 CLAUDE.md `!!` 절대 사용 금지. `requireNotNull(...)` 또는 `attributes.getOrNull(...) ?: error(...)` 사용.

---

## L3: Gradle build cache + Testcontainers — `--no-build-cache` 필요

### 문제
`./gradlew :leader-ktor:test --rerun` 후 재실행 시 Redis container 종료된 상태에서 첫 실패 → 그 이후 `cleanTest` + `test` 도 `FROM-CACHE` 로 통과 표시 (실제 실행 안 됨).

### 교훈
Testcontainers 기반 테스트의 fresh 검증 필요 시:
```bash
./gradlew :module:cleanTest :module:test --no-daemon --no-build-cache
```

`--no-build-cache` 빠지면 gradle 이 input/output 동일성 판단으로 cached 결과 반환.

---

## L4: testApplication { } DSL — `runSuspendIO` vs `runTest`

### 문제
Ktor `testApplication { ... }` 은 실제 코루틴 + 실시간 동작. `runTest` 의 가상 시간과 호환 안 됨.

### 교훈
Ktor 통합 테스트는:
```kotlin
@Test
fun `xxx`() = runSuspendIO {
    testApplication {
        // ...
    }
}
```

또는 `runBlocking { ... }: Unit` (E3 lessons L5). `runTest` 는 사용 금지.

---

## L5: Ktor BOM 사용 + compileOnly 패턴

### 교훈
core 모듈에서 Ktor 의존성:
- `compileOnly(libs.ktor.server.core)` — 사용자가 자체 Ktor 버전 선택 가능
- `testImplementation` 으로 test 시 실제 의존성 주입
- BOM (`io.ktor:ktor-bom`) import 로 모든 ktor-* artifact 버전 일관

`gradle/libs.versions.toml` 에 ktor 버전 + 개별 library entry 추가.
