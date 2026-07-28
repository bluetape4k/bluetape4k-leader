# 문제 389 Dependabot 보안 재정의

## 맥락

Issue #389에서는 기본 분기의 전이적 Netty, Protobuf, Vert.x 및 Jackson 아티팩트에 대한 공개 Dependabot 경고가 보고되었습니다.

## 결정

`bluetape4k-dependencies`에 공통 외부 라이브러리 버전을 유지하고 보안 재정의 표면을 위해 `leader` 종속성 관리의 중앙 카탈로그 별칭을 사용합니다.

## 결과

`leader`는 이제 중앙 Netty 4.1, Protobuf 및 Vert.x 4 BOM을 가져오고 `bt4kVersion(...)`를 통해 경고된 모듈을 제한합니다. Fabric8은 중앙 카탈로그의 7.7.0 라인으로 업데이트되었으며 로컬 AWS SDK BOM 드리프트도 중앙 카탈로그에 맞춰 조정되었습니다.

## 검증

- 종속성 동기화가 `aws2`를 `2.44.9`에서 `2.44.12`로 업데이트했습니다.
- Gradle 종속성 관리는 이제 가져온 `bt4k` 카탈로그에서 Netty, Protobuf 및 Vert.x 버전을 읽습니다.
- `./gradlew build -x test -x k8sTest`가 통과되었습니다.
- 종속성 통찰력을 통해 K8s/Fabric8은 Netty `4.1.133.Final` 및 Vert.x `4.5.27`를 해결하고 Redisson는 Netty `4.2.13.Final`를 해결합니다.
- `buildEnvironment`는 Gradle 플러그인 클래스 경로가 `mysql-connector-j`를 `9.7.0`로, `protobuf-java`를 `4.34.1`로 업그레이드함을 검증했습니다.
- 사용되지 않은 루트 Exposed 플러그인 `apply false` 선언을 제거하면 루트 빌드 스크립트 클래스 경로에서 오래된 플러그인 POM 종속성 표면이 제거되었습니다.
- `examples/migration-gate`에서 사용되지 않는 Exposed 플러그인을 제거하면 종속성 제출에서 마지막 플러그인 POM 소스가 제거되었습니다.

## 미래 노트

일반 외부 라이브러리에 대해 repo-local 보안 핀을 추가하지 마세요. 누락된 별칭을 `bluetape4k-dependencies`에 추가한 다음 다운스트림 저장소의 `bt4k` 카탈로그를 통해 사용합니다. `settings.gradle.kts`에 대한 Dependabot 경고 후 `buildEnvironment`를 검증하세요. 플러그인 클래스 경로 종속성은 일반적인 하위 프로젝트 종속성 관리 외부에 있습니다. 루트 빌드가 실제로 해당 클래스 경로에 플러그인을 필요로 하지 않는 한 `apply false`를 사용하여 루트 `plugins` 블록에 플러그인 별칭을 유지하지 마십시오. 플러그인 작업이 시연된 워크플로의 일부가 아닌 이상 예제에 Exposed 마이그레이션 플러그인을 적용하지 마세요. 런타임 Exposed 마이그레이션 예제에는 필요하지 않습니다.
