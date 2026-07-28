# Lesson: leader-core 테스트 클래스의 명시적 @TestInstance(PER_CLASS)

**날짜**: 2026-05-16 **문제**: #268 **PR**: #272

## 근본 원인

`leader-core`의 6개 구체적인 테스트 클래스에 명시적인 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 주석이 누락되었습니다. `junit-platform.properties`는 이미 `junit.jupiter.testinstance.lifecycle.default=per_class`를 전역적으로 설정했지만 주석이 없었습니다. 즉, 각 클래스에서 수명 주기를 명시적으로 만드는 프로젝트 규칙과 일치하지 않았습니다.

`LeaderStateTest`에는 `companion object : KLogging()`도 부족했습니다.

## 결정

6개 클래스 모두에 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` + `import org.junit.jupiter.api.TestInstance`를 추가합니다. `LeaderStateTest`에 `companion object : KLogging()`를 추가합니다.

주석은 기술적으로 중복되지만(속성 파일에서 다룹니다) 다음과 같습니다.
- `resources/`를 읽지 않고도 수명주기를 볼 수 있게 만듭니다.
- 프로젝트의 다른 모든 테스트 클래스와 일치
- 프로젝트 규칙에 따라 필요함(CLAUDE.md)

## 변경된 파일

- `LeaderStateTest.kt` — 주석 + 동반 개체 + 가져오기
- `LeaderGroupElectionStateTest.kt` — 주석 + 가져오기
- `AsyncLeaderElectorContractTest.kt` — 주석 + 가져오기
- `AsyncLeaderGroupElectorContractTest.kt` — 주석 + 가져오기
- `LeaderElectionOptionsTest.kt` — 주석 + 가져오기
- `LeaderGroupElectionOptionsTest.kt` — 주석 + 가져오기

## 검증

`./gradlew :leader-core:test` — 빌드 success(테스트 failure 없음)

## 향후 지침

`leader-core`(또는 모든 모듈)에 새 테스트 클래스를 추가할 때: `junit-platform.properties`가 이미 기본 수명 주기를 설정한 경우에도 항상 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 및 `companion object : KLogging()`를 포함합니다.
