# Issue #533 backend diagnostics 작업 교훈

## 배경

모든 leader backend의 capability와 connectivity를 공통 모델로 제공하고, Spring Boot와 Ktor가 선택된 elector에서 동일한 진단 정보를 읽게 했다. 구현 범위에는 decorator와 외부 client lifecycle 신호도 포함됐다.

## 결정과 결과

- lifecycle 상태와 reachability를 분리했다. 열린 connection이나 실행 중 client는 `UP` 근거가 아니므로 `UNKNOWN`으로 보고하고, 명시적으로 닫히거나 중지된 상태만 `DOWN`으로 보고한다.
- 수동 상태는 실패를 증명할 수 있어도 성공을 증명하지 못할 수 있다. 성공을 추측하는 대신 보수적인 상태를 반환하면 health가 false-green이 되지 않는다.
- decorator는 선택적 capability를 보존해야 한다. `LeaderBackendDiagnosticsAware`의 nullable provider를 전달해 사용자 정의 elector가 지원하지 않는 기능을 광고하지 않게 했다.
- 테스트도 capability의 부재를 표현해야 한다. `(wrapper as? LeaderBackendDiagnosticsAware)?.backendDiagnosticsProvider`로 읽고 provider가 있는 경우와 없는 경우를 각각 검증했다.
- capability manifest에 runtime source anchor를 연결했다. 문서 표와 실제 descriptor가 다른 방향으로 변경되면 validator가 즉시 drift를 탐지한다.

## 놓친 점과 복구

초기 구현은 세 backend의 lifecycle-running 상태를 connectivity `UP`으로 해석했다. 또한 Micrometer wrapper가 진단 capability를 전달하지 않았다. 독립 아키텍처 검토와 메인 세션 통합 검토가 두 문제를 찾았고, `49cb2e9f`에서 상태 의미와 decorator 전달을 함께 복구했다.

## 검증

- 전체 테스트 3,283개와 Detekt 통과
- ABI artifact 16개 검사, unknown 변경 0
- Core diagnostics targeted test 5개 통과
- Micrometer targeted test 17개 통과
- capability validator unit 11개, self/static 검사 통과
- README JVM 25와 locale inventory 검사 통과

## 향후 방어 조건

active probe가 실제 네트워크 I/O를 시작한다면 `Duration` 인자를 받는 것만으로 timeout을 보장했다고 판단하지 않는다. 호출자 deadline, SDK timeout 전달, 응답 지연과 연결 단절 테스트를 함께 추가하고, probe가 lock 획득·연장·해제와 client 수명주기를 변경하지 않는지 다시 검증한다.
