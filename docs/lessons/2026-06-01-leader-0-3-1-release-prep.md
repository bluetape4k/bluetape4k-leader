# Leader 0.3.1 릴리스 준비

## 맥락

`bluetape4k-leader` 0.3.0은 이미 `bluetape4k-bom` 1.10.0을 사용했지만 Exposed 정렬은 여전히 1.9.2 라인에 있었습니다. 종속성 열차에는 해당 플랫폼을 API 메타데이터로 노출하지 않고 최신 출시된 `bluetape4k-exposed-bom`를 사용하기 위해 게시된 리더 라인이 필요합니다.

## 결정

`0.3.1`를 현재 릴리스-열 정렬(게시 가능한 Exposed 모듈의 `bluetape4k-bom` 1.10.0, `bluetape4k-exposed-bom` 1.10.0 및 `implementation(platform(...))`)에서 패치 릴리스로 게시합니다.

## 결과

패치 릴리스는 종속성만 유지되며 더 광범위한 0.4.0 기능 백로그를 안정적인 릴리스 게이트로 가져오는 것을 방지합니다.

## 검증

- Maven Central은 0.3.0 리더 아티팩트에 대해 HTTP 200을 반환했으며 기존 0.3.0 POM이 이미 `bluetape4k-bom` 1.10.0을 사용하고 있음을 검증했습니다.
- Git 태그 검사에서는 0.3.0이 `bluetape4k-exposed` 1.9.2를 사용하는 것으로 나타났습니다.
- 현재 `develop` CI는 Exposed BOM 구현 범위 변경 후 전달되었습니다.

## 향후 지침

안정적인 릴리스 라인이 이미 핵심 BOM을 포함하지만 인접한 생태계 BOM은 포함하지 않는 경우 전체 마이너 마일스톤을 재개하는 대신 패치 릴리스를 선호합니다.
