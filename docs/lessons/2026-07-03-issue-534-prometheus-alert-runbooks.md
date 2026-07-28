# Issue #534 Prometheus 경고 런북

## 맥락

Prometheus 대시보드 예에는 경고 규칙, Grafana 패널, 실행서 문서 및 운영 리더 선출 신호에 대한 다이어그램이 필요했습니다.

## 결정

- 모든 자산을 `examples/prometheus-dashboard` 아래의 예시 범위로 유지하세요.
- 규칙 및 패널에 대해 현재 내보낸 측정항목만 사용하세요.
- 기본 no-op 싱크는 의도적으로 획득 키를 반환하지 않으므로 기록 경고에서 `NoopLeaderHistorySink`를 제외합니다.
- 활성 게이지 이상 현상을 데모 단일 리더 잠금인 `dashboard-job`로 범위 지정하여 복사된 그룹 선택 워크로드가 거짓 페이지로 표시되지 않도록 합니다.
- 직접적인 임대 연장 수단이 존재할 때까지 임대 위험을 기간 완료 증상으로 처리합니다.

## 결과

이제 이 예에는 Prometheus 경고 규칙, Grafana 경고 패널, 영어/한국어 런북, 렌더링된 경고/런북 다이어그램 및 정적 자산 테스트가 포함되어 있습니다.

## 검증

- `:examples:prometheus-dashboard:test`는 5가지 테스트를 통과했습니다.
- `:examples:prometheus-dashboard:processAot :examples:prometheus-dashboard:processTestAot :examples:prometheus-dashboard:test`가 통과되었습니다.
- `promtool check rules`는 8개의 규칙을 발견했습니다.
- `promtool check config`가 1개의 규칙 파일과 유효한 구성을 찾았습니다.
- 지오메트리 오류, 침입, 교차 없이 다이어그램 감사를 통과했습니다.

## 다음에는

예제 경고를 추가할 때 실행 가능한 예제에 메트릭이 있는지, 기본 무작동 구성 요소가 정상적인 카운터 증분을 생성하는지, 복사된 규칙이 그룹 선택 잠금에 안전한지 여부를 검증하세요.
