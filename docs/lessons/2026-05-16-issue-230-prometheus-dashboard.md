# 이슈 #230 Prometheus 대시보드 예시

## 맥락

Issue #230에는 Prometheus 및 Grafana를 사용하는 리더 AOP 메트릭에 대한 실행 가능한 Spring Boot 예제가 추가되었습니다. 대시보드 채택 경로를 제공하여 #145 작업도 마무리됩니다.

## 결정

이 예에서는 `leader-spring-boot`, `leader-micrometer`, Lettuce Redis, Actuator Prometheus 내보내기, Docker Compose 및 직접 작성한 Grafana 대시보드와 함께 Spring Boot 4 애플리케이션 모듈을 사용합니다. Spring Boot AOT를 직접 적용하고 CI는 스크랩 테스트 전에 `processAot`와 `processTestAot`를 모두 실행합니다.

검증 후 외부 Freefair `aspect(project(":leader-spring-boot"))` 위빙이 거부되었습니다. AspectJ는 `LeaderElectionAspect`를 AspectJ 싱글톤으로 초기화하려고 시도했으며 Spring DI 생성자와 충돌했습니다. 따라서 예제에서는 `@EnableAspectJAutoProxy(proxyTargetClass = true)`를 사용하고 예약된 트리거 Bean을 프록시된 `@LeaderElection` 작업 Bean에서 분리합니다.

이 예에서는 대시보드 레이블이 안정적인 `dashboard-job` 값을 유지하도록 기본 잠금 이름 접두사를 비활성화합니다. 작업의 `Thread.sleep(100)`는 실행 기간을 표시하기 위해 의도적으로 예제 전용 차단입니다.

Docker Compose는 리포지토리 루트에서 빌드되므로 체크인된 `.dockerignore`는 로컬 비밀 및 빌드 아티팩트 패턴과 동기화를 유지해야 합니다. 이것이 없으면 `COPY . .`는 `examples/prometheus-dashboard/.env`, Gradle 출력 또는 에이전트 아티팩트를 이미지 레이어에 넣을 수 있습니다.

`processAot` 및 `processTestAot`는 앱/테스트와 동일한 Redis 대체 경로를 인스턴스화하므로 `DEMO_REDIS_URL`가 기존 Redis 인스턴스를 가리키지 않는 한 Docker가 필요합니다.

## 결과

새로운 `examples/prometheus-dashboard` 모듈은 Gradle, 루트 README 파일, CI, 예제 워크플로 및 AGENTS 지침에 등록되어 있습니다. 로컬 검증에서는 Spring AOT 생성, Spring 테스트 AOT 생성, Prometheus 스크레이핑 측정항목, 워크플로 구문 및 Gradle 프로젝트 등록이 검증되었습니다.

## 검증

- `./gradlew :examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test --no-configuration-cache --console=plain`
- `./gradlew :examples:prometheus-dashboard:build -x test --no-configuration-cache --console=plain`
- `./gradlew projects --no-configuration-cache --console=plain`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly.yml .github/workflows/examples.yml`
- `git diff --check`

## 향후 지침

이 저장소의 사용자 지향 Spring Boot 예제의 경우 외부 AspectJ 측면 경로 위빙이 Spring 관리 생성자 주입 측면과 작동한다고 가정하지 마세요. 메트릭 또는 동작 어설션을 사용하여 실제 조언 경로를 검증하고 `processTestAot`에 Testcontainers 지원 `DynamicPropertySource`가 필요한 경우 Docker 지원 작업에서 Spring AOT 작업을 유지하세요.
