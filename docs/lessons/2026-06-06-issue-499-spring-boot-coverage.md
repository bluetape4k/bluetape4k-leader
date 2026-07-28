# 문제 499 - leader-spring-boot 적용 범위

## 맥락

`leader-spring-boot` 적용 범위는 40% 범위 회귀처럼 보였고 새로운 기준선은 원시 Kover XML을 29.53% 라인 적용 범위로 측정했습니다.

## 결정

이 Spring Boot 라이브러리 모듈에 대한 프로덕션 `main` 소스 세트 클래스만 계산합니다. 생성된 Spring AOT/TestContext Bean 정의 클래스 및 AspectJ 합성 클로저 클래스는 직접 테스트 가능한 프로덕션 동작이 아니라 생성된 계측 아티팩트이므로 Kover 보고서에서 제외합니다.

## 결과

이제 모듈은 87.36% 라인 커버리지(`1327/1519`)를 보고하고 거버넌스 정책은 `leader-spring-boot`를 80% 프로덕션 소스 목표로 기록합니다. 보존 작업 Bean 생성을 차단하고 이벤트 게시자 방출/레지스트리 등록을 관찰하기 위한 작은 테스트가 추가되었습니다.

## 검증

- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.history.LeaderHistoryRetentionAutoConfigurationTest' --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' --no-daemon --no-configuration-cache --console=plain`
  - 9번의 테스트를 통과했습니다.
- `./gradlew :bluetape4k-leader-spring-boot:cleanTest :bluetape4k-leader-spring-boot:test :bluetape4k-leader-spring-boot:koverXmlReport --no-daemon --no-configuration-cache --console=plain`
  - 335개의 테스트를 통과했습니다.
  - `aotTest` 5개 테스트를 통과했습니다.
  - 커버 XML: `LINE 1327/1519 = 87.36%`.
- `git diff --check`

## 향후 지침

하드 Kover 게이트를 무심코 다시 도입하지 마십시오. 이 저장소는 이전에 생성/통합이 많은 모듈의 개발을 차단했기 때문에 엄격한 적용 범위 게이트를 제거했습니다. 사용자가 하드 CI 임계값을 명시적으로 요청하지 않는 한 프로덕션 소스 Kover XML 증거와 검토 정책을 사용합니다.
