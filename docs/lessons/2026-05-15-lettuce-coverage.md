# Lettuce 커버리지 리프트

## 맥락

Issue #236은 `leader-redis-lettuce` 라인 적용 범위를 80% 이상으로 높였습니다.

기준선 Kover 라인 적용 범위는 66.93%였습니다. 첫 번째 적용 범위 실행에서는 `LettuceCandidateRegistry.listCandidates`의 기존 경합이 노출되었습니다. Redis 키는 `SCAN`와 `MGET` 사이에서 만료될 수 있으며 반환된 값이 없으면 Lettuce `KeyValue.value`가 발생합니다.

## 결정

없는 `MGET` 값을 조회와 읽기 사이에 만료된 후보로 처리합니다. 이는 기존 후보 TTL 의미 체계와 일치하며 오래된 키로 인한 복원 또는 failure를 방지합니다.

낮은 수준의 Redis 기본 요소를 직접 테스트하여 적용 범위가 높아졌습니다.

- `LettuceLock` 동기화, 비동기 및 계약 획득/확장/잠금 해제/시간 초과 일시 중지.
- 더 이상 사용되지 않는 `LettuceSemaphore` 동기화, 비동기 및 일시 중지 허용 계약.

## 검증

- `./gradlew :leader-redis-lettuce:test --console=plain`
  - 204개의 테스트가 통과되었습니다.
- `./gradlew :leader-redis-lettuce:koverXmlReportJvm :leader-redis-lettuce:koverLogJvm --console=plain`
  - 회선 적용 범위: 84.6002%.
  - 구문 분석된 XML: `TOTAL_LINE 857/1013 84.60%`.

## 레슨

Redis `SCAN`에 이어 값 읽기가 수행되는 경우 항상 두 작업 사이의 키 소멸을 처리합니다. `hasValue()`가 true가 아닌 이상 Lettuce `KeyValue.value`를 호출하지 마세요.

낮은 수준의 인프라 클래스에 적용 범위 격차가 집중되어 있는 경우 집중적인 계약 테스트를 통해 광범위한 공개 API 시나리오를 추가하는 것보다 더 안전하게 적용 범위를 높일 수 있습니다.
