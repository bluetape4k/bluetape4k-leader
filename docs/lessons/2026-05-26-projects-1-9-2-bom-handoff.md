# 프로젝트 1.9.2 BOM 전달

## 맥락

`bluetape4k-projects` 1.9.2가 출시되었으며 `bluetape4k-bom:1.9.2`는 Maven Central에서 볼 수 있습니다.

## 결정

일치하는 프로젝트 스냅샷 대신 이 릴리스 준비 분기에 안정적인 `bluetape4k-bom` 1.9.2 라인을 사용하세요. 이 핸드오프는 이미 출시된 프로젝트 BOM만 승격하므로 Exposed BOM 참조를 현재 라인에 유지합니다.

## 결과

버전 카탈로그는 이제 안정적인 1.9.2 릴리스에서 `io.github.bluetape4k:bluetape4k-bom`를 해결하면서 이 저장소의 자체 릴리스 라인은 변경되지 않은 채로 둡니다.

## 검증

- `bluetape4k-bom:1.9.2`용 Maven Central HTTP 200
- `./gradlew help --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache`
