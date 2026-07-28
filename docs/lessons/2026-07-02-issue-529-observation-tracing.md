# 이슈 #529 관찰 추적 수업

## 맥락

#529는 기존 Micrometer 측정기 옆에 Micrometer 관찰 지원을 추가했습니다. 요청은 테스트에서 예제 코드와 자세한 README 범위를 포함하도록 확장되었습니다.

## 결정

- 현재 AOP 측정항목 SPI에는 안전한 동일 잠금 페어링을 위한 호출별 ID가 없으므로 수명이 긴 범위 대신 독립형 터미널 관찰을 내보냅니다.
- 원시 잠금 이름, 리더 ID 및 원시 던질 수 있는 세부 정보를 선택적으로 유지하세요.
- Spring AOP에서 `leader.id`를 합성하지 마십시오. 현재 주석은 실제 리더 신원 계약을 노출하지 않습니다.
- Micrometer가 연장 결과를 일관되게 기록할 수 있으려면 `LockExtender`에 코어 후크가 필요하기 때문에 임대 연장 관찰을 #559로 연기하세요.

## 결과

- `leader-micrometer`는 이제 관찰 레코더/리스너 API를 제공합니다.
- `leader-spring-boot`는 `ObservationRegistry`가 있는 경우 관찰 레코더/리스너 빈을 자동 구성합니다.
- `examples/prometheus-dashboard`에는 로컬 데모 `ObservationHandler`가 포함되어 있습니다.
- README 및 README.ko 파일은 직접, Spring Boot 및 예제 사용법을 설명합니다.

## 검증

- 마이크로미터 관찰, 스프링 관찰 자동 구성, 기존 Micrometer 자동 구성 및 Prometheus 대시보드 예에 대한 집중 Gradle 테스트를 통과했습니다.
- 종속성 검사를 통해 이 문제가 OpenTelemetry SDK, Micrometer 추적 브리지, 내보내기 또는 수집기 종속성을 추가하지 않았음을 검증했습니다.
- `git diff --check`가 통과되었습니다.

## 퓨쳐 가드

경과된 숫자 값은 카디널리티가 낮은 관찰 키 값이 아니어야 합니다. 무제한 숫자 진단을 위해 높은 카디널리티 키 또는 태그가 아닌 컨텍스트 메커니즘을 사용합니다.
