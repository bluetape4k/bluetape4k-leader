# Gradle 보안 수정에서 의존성 해석 범위를 구분하기

## 배경

이슈 #974의 Maven Dependabot 경보 25건은 `settings.gradle.kts` manifest에 연결되어 있었다. 기존 규칙은 Dokka 구성만 정렬해 Spring Boot의 프로젝트 런타임·테스트 구성에 남은 Jackson 취약 버전을 막지 못했다.

## 원인

Gradle 프로젝트 구성과 플러그인의 `buildscript` classpath는 별도 의존성 그래프다. 프로젝트의 `resolutionStrategy`를 확인해도 플러그인 classpath의 Jackson 버전은 검증되지 않는다. Spring Dependency Management 플러그인도 BOM과 POM을 처리할 때 자체 detached configuration을 만든다. 프로젝트 구성에 등록한 `resolutionStrategy`는 이 detached configuration에 자동 적용되지 않으므로 플러그인 내부 그래프를 따로 검증해야 한다. Dokka와 Kotlin 게시 검증처럼 별도로 만든 구성도 각각 실제 해석 결과를 확인해야 한다.

## 결정

- 프로젝트 구성에는 Spring Dependency Management의 BOM 규칙이 적용된 뒤 중앙 카탈로그의 Jackson 2·3 버전을 적용한다.
- Spring Dependency Management가 만드는 detached configuration에는 루트 `dependencyManagement.resolutionStrategy.force(...)`로 중앙 카탈로그 버전을 적용한다.
- `settings.gradle.kts`에서 같은 카탈로그를 읽어 플러그인 classpath의 Jackson과 FreeMarker를 별도로 정렬한다.
- Dokka 구성의 FreeMarker·jsoup과 Kotlin 게시 검증 구성의 Bouncy Castle은 실제 사용하는 도구 구성에만 적용한다.

현재 적용 위치는 [`build.gradle.kts`](../../build.gradle.kts)와 [`settings.gradle.kts`](../../settings.gradle.kts)다. 공유 카탈로그를 바꾸지 않고 Leader 저장소의 해석 규칙으로 경보가 가리키는 그래프를 정렬했다.

## 검증과 결과

- 수정 전 `dependencyInsight` 재현은 `bluetape4k-leader-core`의 Jackson 2 `2.21.6`, `bluetape4k-leader-spring-boot`의 Jackson 2 `2.21.5`, Jackson 3 `3.1.5`를 확인했다.
- 수정 후 프로젝트 런타임·테스트 구성, 루트와 하위 프로젝트의 `buildEnvironment`, `dokkaHtmlGeneratorRuntimeResolver~internal`, `kotlinBouncyCastleConfiguration`에서 중앙 카탈로그 버전을 확인했다.
- 첫 Spring Boot 테스트 실행은 Docker 환경을 찾지 못해 실패했다. Colima와 Docker context가 정상인지 확인한 뒤 Testcontainers socket 환경을 지정해 새 Gradle 프로세스로 다시 실행했다. 재실행 결과는 테스트 631개, 실패 0, 오류 0, 건너뜀 0이었다.

  ```sh
  DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock" \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    ./gradlew --no-daemon --no-configuration-cache --no-parallel \
      :bluetape4k-leader-spring-boot:test --console=plain
  ```

- 전체 테스트는 별도 Spring Boot 실행에서 확인했고, 아래 빌드에서는 테스트를 제외해 컴파일과 binary compatibility 결과를 구분했다. ABI 결과는 artifacts 16, ignored 40, unknown 0이었다.

  ```sh
  ./gradlew --no-daemon --no-configuration-cache --no-parallel \
    build -x test checkBinaryCompatibility --console=plain
  ```

- 로컬 수정만으로는 GitHub 경보 상태가 바뀌지 않는다. 2026-10-16 최초 확인에서는 Maven 경보 25건이 열려 있었고, 통합 후 GitHub에서 다시 확인해야 했다.
- 루트 `dependencyManagement.resolutionStrategy` 보완 뒤 `:ForceDependencyResolutionPlugin_resolveAllDependencies`를 다시 실행했다. 그래프의 1,036개 해석 항목에서 기준 취약 좌표 12쌍(Jackson 2 `2.15.3`, jsoup `1.16.1`, FreeMarker `2.3.32`, Bouncy Castle `1.84`)이 사라졌다. `nmcpAggregation`은 직접 프로젝트 17개를 유지했고, 루트의 큰 detached aggregate는 305개 항목과 직접 프로젝트 33개를 유지했다. SNAPSHOT 해석에 따라 전체 항목 수는 달라질 수 있으므로 버전 좌표와 집계 범위를 비교한다.
- 2026-10-17 live Dependabot 조회에서는 열린 경보 18건(critical 2, high 11, medium 5)이 남아 있었다. 같은 날 만든 로컬 그래프에는 해당 패키지가 Jackson Core/Databind `2.22.3`, jsoup `1.23.2`, FreeMarker `2.3.35`, Bouncy Castle `bcprov-jdk18on:1.85.2`로 나타났고, 18개 경보의 최초 수정 버전 기준보다 낮은 해석 버전은 없었다. GitHub 경보 종료는 변경 통합과 Dependency Submission 이후에 다시 확인한다.
- 같은 변경에서 `./gradlew :bluetape4k-leader-core:test --rerun-tasks --no-daemon --console=plain`을 실행해 테스트 1,082개가 통과했고 configuration-cache 항목을 저장했다.

## 향후 지침

Dependabot 또는 중앙 카탈로그 경보를 처리할 때는 패키지별 manifest와 실제 scope를 확인한 뒤 프로젝트 런타임·테스트의 `dependencyInsight`, 루트와 하위 프로젝트의 `buildEnvironment`, Spring Dependency Management의 detached configuration, Dokka 해석 구성, 사용자 정의 검증 구성을 각각 점검한다. 프로젝트 구성의 규칙은 Spring Dependency Management의 detached configuration에 자동 적용되지 않으므로 해당 플러그인의 `dependencyManagement.resolutionStrategy`에도 보안 버전을 등록한다. 한 구성에서 정렬된 버전이 다른 Gradle 구성에도 적용됐다고 추정하지 않는다. 영향 모듈 테스트와 전체 컴파일, 바이너리 호환성 검증을 마친 뒤에만 수정 결과를 보고하고, 경보 해결 여부는 통합 후 GitHub 상태로 확인한다.
