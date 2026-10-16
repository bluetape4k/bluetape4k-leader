# Gradle 보안 수정에서 의존성 해석 범위를 구분하기

## 배경

이슈 #974의 Maven Dependabot 경보 25건은 `settings.gradle.kts` manifest에 연결되어 있었다. 기존 규칙은 Dokka 구성만 정렬해 Spring Boot의 프로젝트 런타임·테스트 구성에 남은 Jackson 취약 버전을 막지 못했다.

## 원인

Gradle 프로젝트 구성과 플러그인의 `buildscript` classpath는 별도 의존성 그래프다. 프로젝트의 `resolutionStrategy`를 확인해도 플러그인 classpath의 Jackson 버전은 검증되지 않는다. Dokka와 Kotlin 게시 검증처럼 별도로 만든 구성도 각각 실제 해석 결과를 확인해야 한다.

## 결정

- 프로젝트 구성에는 Spring Dependency Management의 BOM 규칙이 적용된 뒤 중앙 카탈로그의 Jackson 2·3 버전을 적용한다.
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

- 로컬 수정만으로는 GitHub 경보 상태가 바뀌지 않는다. 확인 시점에도 Maven 경보 25건은 열려 있었으며, 통합 후 GitHub에서 다시 확인해야 한다.

## 향후 지침

Dependabot 또는 중앙 카탈로그 경보를 처리할 때는 패키지별 manifest와 실제 scope를 확인한 뒤 프로젝트 런타임·테스트의 `dependencyInsight`, 루트와 하위 프로젝트의 `buildEnvironment`, Dokka 해석 구성, 사용자 정의 검증 구성을 각각 점검한다. 한 구성에서 정렬된 버전이 다른 Gradle 구성에도 적용됐다고 추정하지 않는다. 영향 모듈 테스트와 전체 컴파일, 바이너리 호환성 검증을 마친 뒤에만 수정 결과를 보고하고, 경보 해결 여부는 통합 후 GitHub 상태로 확인한다.
