# 문서 버전 및 로케일 패리티 7계층 검토

날짜: 2026-07-04 범위: Issue #578, 마일스톤 0.5.0

## 검토된 모듈

- 루트 및 모듈 README 종속성 조각.
- `leader-redis-redisson` 영어 및 한국어 README 패리티.
- `leader-spring-boot` 공개 Spring 구성 메타데이터입니다.

## 7계층 결과

1. 정확성: 통과
   - 오래된 `0.3.0` 종속성 조각이 현재 안정적인 `0.4.0` 릴리스로 업데이트되었습니다.
   - Spring 구성 메타데이터는 유효한 JSON으로 유지됩니다.

2. API 및 계약 호환성: 통과
   - 프로덕션 API 또는 동작이 변경되지 않았습니다.
   - 이제 공개 문서에서는 Redisson 비동기 실행기 사용을 전용 가상 스레드 선택기 유형과 구별합니다.

3. 동시성 및 취소: PASS
   - 런타임 경로에 대한 문서 전용 변경 사항입니다.
   - 비동기, 코루틴, 워치독 또는 릴리스 코드가 변경되지 않았습니다.

4. 백엔드 소유권 안전성: 통과
   - 종속성 조각은 이제 사용자에게 안정적으로 출시된 아티팩트 열차를 가리킵니다.
   - Redisson 문서는 더 이상 존재하지 않는 `RedissonVirtualThread*` API를 암시하지 않습니다.

5. 테스트 및 점검: 통과
   - Spring Boot 메타데이터 JSON이 success적으로 구문 분석되었습니다.
   - `leader-spring-boot` 리소스 및 Kotlin 컴파일이 통과되었습니다.
   - Diff 공백 검사가 통과되었습니다.

6. 보안 및 관찰 가능성: 통과
   - Public Spring 메타데이터 설명은 영어입니다.
   - AOP SpEL 메타데이터는 이제 메소드 해결 프로그램 위험을 영어로 설명합니다.

7. 유지보수성: 합격
   - 영어 및 한국어 Redisson README 파일은 호출 팩토리 섹션의 소스와 동일합니다.
   - `0.3.0`에 대한 버전 드리프트 스캔은 이제 README 일치 항목을 반환하지 않습니다.

## 검증 증거

- `rg -n "0\\.3\\.0" README.md README.ko.md leader-*/README.md leader-*/README.ko.md`
- `rg -n "[가-힣]" leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- `jq empty leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- `./gradlew :bluetape4k-leader-spring-boot:processResources :bluetape4k-leader-spring-boot:compileKotlin --warning-mode all`
- `git diff --check`

## Deferred 검증

전체 저장소 테스트는 요청된 워크플로우에 따라 전체 스택 이슈 트레인이 구현될 때까지 의도적으로 연기됩니다.
