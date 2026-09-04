# 비동기 leader lifecycle의 취소와 executor 거부 정리

## 맥락

Issue #857과 #858은 `runAsyncIfLeaderResult`가 반환한 future를 취소하거나,
lock을 획득한 직후 executor가 작업 제출을 거부할 때 실제 action과 lease의
lifecycle이 끝나지 않는 문제를 다룹니다. 반환 future의 terminal 상태만 바뀌고
내부 action이 계속 실행되면 lock 재획득이 지연됩니다. Redis backend에서는
획득과 action 제출 사이의 실패 때문에 이미 얻은 lease나 group permit이 남을 수
있습니다.

## 원인

- 결과 future의 취소 대상이 바깥쪽 source future에만 연결되어 실제 action
  future까지 전달되지 않았습니다.
- `thenComposeAsync` 계열 pipeline은 lock 획득 뒤 executor 제출이 실패하면 action
  callback에 진입하지 않으므로 callback 내부의 release 경로도 실행하지 않습니다.
- 취소와 정상 완료가 경쟁할 때 여러 callback이 cleanup을 시작할 수 있으므로,
  cleanup owner를 하나로 고정하지 않으면 중복 release 위험이 생깁니다.

## 결정

- 결과 변환과 backend pipeline 사이에 cancellation relay를 두고, 실제 action
  future가 정해지는 즉시 취소 대상을 연결합니다.
- lock 획득 이후에는 action 실행 여부와 관계없이 terminal 경로가 lease cleanup을
  소유합니다. executor rejection도 cleanup이 끝난 뒤 원래 예외로 완료합니다.
- cancellation, action 완료, 제출 실패가 경쟁해도 원자적 owner가 cleanup을 정확히
  한 번만 시작하도록 합니다.

## 결과와 검증

반환 future를 취소하면 실제 action future도 취소되고, cleanup 완료 뒤 같은 lock을
다시 획득할 수 있습니다. Lettuce, Redisson, MongoDB의 단일/group 경로는 두 번째
executor 제출을 의도적으로 거부하는 회귀 테스트로 lease와 permit 회수를
검증했습니다.

- RED: 실제 action이 취소되지 않고 재획득이 `null`이 되는 동작을 재현했습니다.
- GREEN: 영향 모듈 9개의 전체 suite 2,571개가 한 차례 통과했고, 최종 CAS 수정 뒤
  core/MongoDB/Lettuce/Redisson lifecycle 회귀 16개가 다시 통과했습니다.
- `./gradlew detekt --no-daemon --console=plain`이 통과했습니다.
- `git diff --cached --check`가 통과했습니다.
- 최종 core 전체 재실행은 기존 Issue #868의
  `BoundedLeaderAuditExporterTest` flake 1건을 두 번 재현했습니다. 해당 테스트의
  격리 재실행은 통과했으며 이번 lifecycle 변경과 겹치는 source는 없습니다.

## 놓친 가정과 향후 지침

`CompletableFuture.cancel()`은 연결된 비동기 작업 전체를 자동으로 취소하지
않습니다. future adapter를 추가하거나 수정할 때는 반환 future, acquire future,
실제 action future, watchdog, lease cleanup의 ownership을 각각 확인해야 합니다.
회귀 테스트는 `isCancelled`만 검사하지 말고 action 취소와 동일 lock/slot 재획득을
함께 검증합니다. acquire 뒤 비동기 제출이 있는 backend는 executor rejection을
별도 terminal 경로로 취급하고, 원래 실패가 관찰되기 전에 cleanup이 끝나는지도
검증합니다.
