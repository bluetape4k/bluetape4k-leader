# Exposed BOM 구현 범위

## 맥락

`bluetape4k-dependencies 1.2.0` 릴리스 트레인은 `bluetape4k-exposed-bom`를 `1.10.0`로 승격합니다. Leader에는 공개 API BOM이 아닌 Exposed 모듈 테스트 및 정렬을 위한 bluetape4k Exposed 라인만 필요합니다.

## 결정

게시 가능한 Exposed 리더 모듈에서 `implementation(platform(...))`와 함께 `bluetape4k-exposed-bom`를 가져오고 테스트 도우미 별칭을 동일한 `1.10.0` 라인에 맞춰 유지합니다.

## 결과

이를 통해 게시된 메타데이터에서 API 범위 bluetape4k Exposed BOM 플랫폼을 방지하면서 로컬 컴파일/테스트 그래프를 릴리스 트레인과 정렬된 상태로 유지합니다.

## 검증

- Maven Central은 `bluetape4k-exposed-bom:1.10.0`, `bluetape4k-exposed-jdbc:1.10.0`, `bluetape4k-exposed-jdbc-tests:1.10.0` 및 `bluetape4k-exposed-r2dbc-tests:1.10.0`에 대해 HTTP 200을 반환했습니다.
- `./gradlew :bluetape4k-leader-exposed-core:build :bluetape4k-leader-exposed-jdbc:build :bluetape4k-leader-exposed-r2dbc:build --no-daemon --console=plain`가 통과되었습니다.

## 향후 지침

나중에 리더에 bluetape4k Exposed 런타임 아티팩트가 필요한 경우 의도적으로 구체적인 아티팩트 종속성을 추가하세요. BOM 플랫폼을 `api`로 승격하지 마십시오.
