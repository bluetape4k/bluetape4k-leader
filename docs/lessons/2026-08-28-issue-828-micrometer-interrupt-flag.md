# #828 Micrometer diagnostics probe의 interrupt flag 보존

## 문제

Micrometer가 backend diagnostics probe를 감싸면서 `InterruptedException`을 같은
인스턴스로 재전파했지만, 인터럽트로 지워진 현재 스레드의 interrupt flag를 복원하지
않았다. 호출자는 예외를 받더라도 상위 취소·종료 경로가 인터럽트 상태를 확인할 수
없었다.

## 수정과 예방 규칙

실패한 가정: 예외 인스턴스를 그대로 던지면 blocking interrupt 계약이 충분하다.

발견 증거 또는 교정: `recordActiveProbe`는 예외 identity만 보존했고
`Thread.currentThread().isInterrupted`는 `false`로 남았다.

수정 결정: `InterruptedException`을 잡는 즉시 `Thread.currentThread().interrupt()`를
호출한 뒤 같은 예외를 다시 던진다. `CancellationException`, 일반 예외의 메트릭
fallback, `Error` 재전파 경계는 기존 동작을 유지한다.

향후 예방 확인: 동기 probe를 장식하거나 로깅하는 새 경계마다
`checkConnectivity`와 `diagnostics(probe=true)` 각각에 대해 동일 인스턴스 재전파와
interrupt flag 복원을 함께 검증한다.

## 검증

- `InstrumentedLeaderElectorsTest.active connectivity probes restore interrupt flag and preserve exception identity`: passing
- 기존 Micrometer decorator 테스트와 `Error`/`CancellationException` 예외 경계: 회귀 테스트에서 확인
