# 중앙 릴리스 예 상위 제외

## 맥락

`examples` 게시가 생성되었기 때문에 첫 번째 `0.1.0` 태그 이후 중앙 검증이 failure했습니다. 중첩된 Gradle에는 `:examples` 및 `:examples:*` 생성이 모두 포함됩니다. `:examples:*`만 필터링하면 게시 경로에 상위 프로젝트가 남습니다.

## 결정

NMCP 설정, 집계, 게시/서명 설정 및 적용 범위 등록에서 `:examples` 및 `:examples:*`를 모두 제외합니다. 생성된 POM에 종속성 버전이 기록되도록 Spring 종속성 관리 POM 사용자 정의를 활성화된 상태로 유지하세요.

## 결과

이제 리더 릴리스 메타데이터에는 라이브러리 모듈과 BOM만 포함됩니다.

## 검증

- `./gradlew clean generatePomFileForBluetapeLeaderPublication --no-daemon --no-configuration-cache --no-build-cache`
- 생성된 BOM POM 스캔에서 `examples`, `demo` 또는 `benchmark` 항목을 발견하지 못했습니다.
