# #919: nullable 값으로 실행 여부를 추론하지 않는다

BatchScheduler는 실행된 작업이 null을 반환해도 경합 로그를 남겼다. 실제 Redis와 Logback 캡처에서
완료 로그 1건과 잘못된 skip 로그 1건을 확인했다. 값과 실행 상태는 다른 계약이다.

기존 LeaderElector.runIfLeaderResult와 LeaderRunResult로 분기한다. Elected는 value를 반환하고
Skipped에서만 skip을 기록하며 ActionFailed.cause는 원본 그대로 던진다.
일반 action 예외를 결과로 감싸는 helper로 옮길 때 기존 throw 계약을 누락하지 않아야 한다.
같이 검증한 InterruptedException은 이전 경로에서 flag가 남지 않았고, 기존 result helper가
명시적으로 interrupt를 복원하는 계약을 재사용해 통과했다. CancellationException도 원본을 유지한다.

## 검증 및 직접 검토

- RED 2건: elected-null 로그 오류, interrupt flag false.
- 전체 BatchScheduler 14건 및 detekt PASS: 실제 경합 로그, action/cancel/interrupt 원본,
  오류 후 재획득과 기존 single-leader 통합 테스트 포함.
- diff check PASS. library ABI·dependency·workflow 변경 없음.
- 직접 코드·설계 검토 P0/P1=0 WATCH. native 검토 반복 timeout은 독립 PASS가 아니며 승인된 inline fallback을 사용했다.
- logger 캡처는 기존 Logback만 사용하고 반드시 detach/stop한다. 테스트 설정은 parallel.enabled=false다.
- 실행 뒤 connection close 경고는 기존 ShutdownQueue 등록과 테스트 종료 close의 중복이며 테스트 실패가 아니다.

T? 반환형의 모호성 자체를 없앴다고 주장하지 않는다. non-null 작업에 한해서만 null을 경합으로 해석한다.
새 wrapper나 의존성 없이 기존 ecosystem 결과 API를 사용하고 README 두 언어 및 KDoc을 맞춘다.
SPW-01~05/KO-01~07: 이슈·실행·core API에 근거한 한국어 문서, 오류 전파·한계 확인 및 read-back.
