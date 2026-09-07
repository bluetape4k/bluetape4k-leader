# Issue #897 Ktor 공통 리소스 lifecycle 연결 구현 계획

**목표:** `leader-ktor`의 종료 진입점을 projects 공통 lifecycle에 연결하면서
Leader 전용 비동기 cleanup과 report 계약을 보존한다.

**구조:** 공통 `ApplicationResourceRegistry`는
`LeaderElectionResourceRegistryImpl.close()`를 application-owned 동기식 action으로
소유한다. Leader registry는 job, resource, timeout과 report를 계속 소유한다.

**기술:** Kotlin/JVM, Ktor 3.x, kotlinx-coroutines, JUnit 5,
`bluetape4k-ktor-core:2.1.0-SNAPSHOT`, Gradle publication metadata.

## 1. 기준선과 변경 파일

- 기준 SHA: `28a5be64d547e5be77251e7b0f7a4eb195d15d23`
- Modify: `leader-ktor/build.gradle.kts`
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginTest.kt`
- Modify: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderElectionResourceRegistryTest.kt`
- Create: `docs/lessons/2026-09-07-issue-897-ktor-resource-lifecycle.md`

## 2. 실행 순서

### Task 1: composition 계약을 RED로 고정

- [ ] test application에서 공통 lifecycle registry가 재사용되는지 확인한다.
- [ ] application stop이 Leader registry를 닫고 기존 shutdown report를 확정하는지
  확인한다.
- [ ] 공통 lifecycle과 Leader registry의 반복 close가 resource를 한 번만 닫는지
  확인한다.
- [ ] resource close 중 재진입한 `close()`가 deadlock이나 중복 close를 만들지
  확인한다.
- [ ] 종료 뒤 late registration이 즉시 close/cancel되는지 확인한다.
- [ ] async resource timeout 뒤 새 resource가 즉시 close되고 report의 기존 timeout
  필드가 유지되는지 확인한다.
- [ ] production 변경 전 대상 테스트를 실행해 공통 lifecycle 연결 assertion이
  실패하는 RED 증거를 남긴다.

### Task 2: 최소 composition 구현

- [ ] `leader-ktor`에 `implementation(bt4k.bluetape4k.ktor.core)`를 추가한다.
- [ ] plugin 설치 시 `installApplicationResourceLifecycle()`를 호출한다.
- [ ] shutdown observer를 즉시 설치하고 Leader registry를 공통 registry에 등록한다.
- [ ] Leader 전용 `ApplicationStopped` hook을 제거해 lifecycle owner를 하나로 만든다.
- [ ] public API와 Leader registry cleanup 구현은 변경하지 않는다.

### Task 3: GREEN과 회귀 검증

- [ ] 새 대상 테스트를 통과시킨다.
- [ ] `LeaderElectionResourceRegistryTest`, `LeaderElectionPluginTest`,
  `ApplicationExtTest`를 함께 통과시킨다.
- [ ] `:bluetape4k-leader-ktor:test` 전체를 실행한다.
- [ ] `:bluetape4k-leader-ktor:detekt`와 `checkBinaryCompatibility`를 실행한다.

### Task 4: dependency와 publication 검증

- [ ] `dependencies --configuration runtimeClasspath`에서
  `bluetape4k-ktor-core:2.1.0-SNAPSHOT` 해석을 확인한다.
- [ ] publication POM을 생성하고 `bluetape4k-ktor-core` runtime dependency와
  catalog/BOM 기반 버전을 확인한다.
- [ ] 임시 dependency override나 별도 repository가 없는지 diff로 확인한다.
- [ ] `git diff --check`를 실행한다.

### Task 5: 결정 기록과 전달

- [ ] 기존 #452 lesson의 조건부 변경을 새 lesson에 기록한다.
- [ ] exact diff 독립 리뷰를 `gpt-5.6-luna max`로 수행한다.
- [ ] P0/P1을 수정하고 대상 검증을 다시 실행한다.
- [ ] Korean-first PR body에 `Closes #897`, dependency/POM 증거와
  `## DoD Status`를 기록한다.
- [ ] remote exact head와 CI, review/thread, mergeability를 읽어 merge-ready 상태를
  보고한다. merge는 별도 승인 전 실행하지 않는다.

## 3. 검증 명령

```bash
./gradlew :bluetape4k-leader-ktor:test --no-daemon --no-build-cache
./gradlew :bluetape4k-leader-ktor:detekt checkBinaryCompatibility --no-daemon --no-build-cache
./gradlew :bluetape4k-leader-ktor:dependencies --configuration runtimeClasspath --no-daemon
./gradlew generatePomFileForBluetape4kLeaderPublication --no-daemon
git diff --check
```

publication task 이름은 live task 목록에서 확인한 뒤 실행한다. 존재하지 않는 task를
성공 증거로 대체하지 않는다.

## 4. 중단 조건

- 해석된 `2.1.0-SNAPSHOT` artifact에 필요한 API가 없거나 publication POM이 정상
  dependency를 만들지 못하면 PR 생성 전 `BLOCKED`로 전환한다.
- public ABI 변경, Leader report 축소, Ktor stop callback blocking이 필요해지면
  승인된 설계의 범위를 벗어나므로 구현을 중단하고 설계를 다시 검토한다.
- 기능·로컬 검증·독립 리뷰·PR 생성까지는 이번 요청의 승인 범위다. merge는 fresh
  exact-head 승인 전 실행하지 않는다.
