# Issue #703: Detekt configuration cache guard 교훈

## 문제

`detektProductionSourceGuard`의 `doLast`가 실행 시점에 `subprojects`,
`rootProject`, `Project.fileTree`, script `logger`를 참조하면 Gradle
configuration cache가 script receiver를 직렬화하려고 시도한다. 기본
`./gradlew detekt`가 guard 단계에서 실패하고 `--no-configuration-cache`만
통과하는 상태는 CI가 회귀를 가린다.

## 적용한 규칙

- 프로젝트 모델에서 필요한 source root의 절대 경로와 모듈 경로만 구성 단계에
  불변 자료로 계산한다.
- guard task가 source root 디렉터리를 입력으로 선언해 파일 변경 시 캐시가
  무효화되도록 한다.
- `doLast`에서는 캡처된 문자열을 `File`로 순회하고 명시적인 task logger만
  사용한다. 실행 action 안에서 Gradle `Project`나 script receiver를 다시
  조회하지 않는다.
- production source가 하나도 없을 때 실패하는 기존 규칙과 모듈별 진단
  로그 형식은 유지한다.

## 검증

정적 guard 계약 회귀 테스트와 실제 Gradle 명령을 함께 실행한다.

- `./gradlew detekt --no-daemon --console=plain`
- `./gradlew detekt --no-configuration-cache --no-daemon --console=plain`
- `./gradlew detektProductionSourceGuard --no-daemon --console=plain`
- `./gradlew detektProductionSourceGuard --no-configuration-cache --no-daemon --console=plain`

두 Detekt 명령과 두 guard 명령은 `BUILD SUCCESSFUL`이며, 기본 Detekt 실행은
configuration cache 재사용까지 확인했다. 최신 develop에 이미 반영된
diagnostics probe의 기존 예외 전파 테스트도 함께 통과해 이번 build guard
수정이 core 동작을 건드리지 않음을 확인했다.

## 재발 방지

새로운 Gradle task action은 구성 단계의 `Project` 객체를 캡처하지 않고,
입력 프로퍼티와 task receiver를 통해서만 실행 데이터를 읽는다. 기본 명령과
`--no-configuration-cache` 명령을 모두 정기적으로 실행해 CI의 비구성 캐시
경로가 기본 configuration-cache 회귀를 가리지 않도록 한다.
