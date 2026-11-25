# Issue #1029 교훈 — testFixtures capability와 Maven POM 경고

## 배경

`leader-core`는 `java-test-fixtures`를 적용하므로 `testFixturesApiElements`와
`testFixturesRuntimeElements` 변형을 제공합니다. Gradle publication을 POM으로
생성할 때 이 변형의 capability는 Maven 모델에 대응하지 않아 metadata warning이
반복 출력되었습니다.

## 원인과 판단

Gradle Module Metadata(`module.json`)는 capability와 변형을 표현할 수 있지만,
Maven POM은 같은 정보를 표현하지 못합니다. 따라서 POM 생성 경고는 실제
Gradle metadata의 손실 경계를 알리는 신호이며, `suppressAllPomMetadataWarnings`
같은 전역 suppression으로 숨기면 다른 publication 경고까지 놓칠 수 있습니다.

publication에는 `testFixturesApiElements`와 `testFixturesRuntimeElements`에만
`suppressPomMetadataWarningsFor`를 적용했습니다. Gradle Module Metadata 생성은
그대로 유지하고, Maven POM에는 test-fixtures 전용 분류자가 혼입되지 않는 기존
계약을 회귀 테스트로 고정했습니다.

## 검증 규칙

- POM 생성 로그에 `Declares capability ...-test-fixtures`가 없어야 합니다.
- 생성된 POM에는 `testFixtures`가 없어야 합니다.
- 생성된 `module.json`에는 두 test-fixtures 변형과
  `...-test-fixtures` capability가 남아 있어야 합니다.
- suppression 범위는 test-fixtures 변형 이름보다 넓히지 않습니다.

이 규칙은 Maven 호환성을 위해 Gradle 전용 metadata를 삭제하지 않으면서도,
publication 로그를 실패 원인과 무관한 반복 경고로 오염시키지 않도록 합니다.
