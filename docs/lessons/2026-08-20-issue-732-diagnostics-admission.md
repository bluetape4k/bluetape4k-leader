# Issue #732 — audit diagnostics admission gate 선형화

## 맥락

OBS-03 AUD-01의 `BoundedLeaderAuditExporter`는 observer callback을 별도
diagnostics queue와 virtual-thread worker로 전달합니다. 기존 worker는 queue에서
항목을 꺼내고 `diagnosticsQueued` permit을 줄인 뒤에야
`diagnosticsLock`을 잡아 slot의 `active` 상태와 callback reservation을
확인했습니다. 그 사이 `close()` 또는 observer handle `close()`가 queue를
drain하거나 slot을 비활성화하면 poll, permit 반환, reservation 순서가 서로 다른
경계에 놓였습니다.

## 원인과 결정

poll 직후 lock을 획득하는 방식은 close가 queue를 비운 뒤 worker가 permit을
감소시키는 경로를 허용합니다. 이 경로에서는 close가 반환된 시점의 callback
admission 상태와 worker가 나중에 관찰하는 slot 상태가 일치하지 않습니다.

다음 동작을 `diagnosticsAdmissionLock` 하나의 짧은 critical section으로
선형화했습니다.

- worker의 queue poll과 `diagnosticsQueued` permit 반환
- gate 및 slot `active` 확인과 callback `running` reservation
- observer handle close, 정상 close, observer `Error`의 queued diagnostics drain
- callback `finally`의 slot `running` release

submit-side observation enqueue는 기존처럼 `tryLock()`만 사용해 callback이나
queue wait로 caller를 막지 않습니다. 이미 reservation된 callback은 close 이후에도
현재 호출을 마칠 수 있지만, close 반환 뒤 새 callback은 시작하지 않습니다.

## 결과

worker가 queue item을 꺼내는 순간부터 callback reservation까지 동일한 admission
gate 아래에서 처리합니다. 따라서 observer close는 poll과 reservation 사이를
통과하지 못하고, inactive item과 normal/fatal drain은 `diagnosticsQueued`를
정확히 한 번만 반환합니다. callback 완료 release도 같은 gate를 사용해 동시
observation enqueue와 permit 상태가 교차하지 않습니다.

## 검증

- RED: blocking diagnostics queue barrier의 targeted test가 기존 구현에서
  `0 passing, 1 failing`으로 실패했습니다. worker가 poll 중인 동안 observer
  close가 먼저 반환되는 경계를 재현했습니다.
- GREEN: `BoundedLeaderAuditExporterTest`가 `21 passing`으로 통과했습니다.
- 회귀 범위에는 inactive/normal/fatal drain 뒤 `diagnosticsQueued == 0`, observer
  replacement의 capacity 2 전량 re-admission, poll/reservation gate barrier가
  포함됩니다.
- `./gradlew :bluetape4k-leader-core:test --no-daemon --rerun-tasks`가
  `996 passing`으로 통과했습니다.
- `./gradlew :bluetape4k-leader-core:detekt --no-daemon --rerun-tasks`와
  `git diff --check`가 통과했습니다.
- Korean terminology audit가 `findings=0`으로 통과했습니다.

## 놀라움과 복구

첫 lifecycle 회귀 테스트에서 `observerDrops == 0`을 함께 요구했지만,
submit-side `tryLock()`은 worker가 짧은 reservation 구간을 선점하면 계약에 따라
관찰 항목을 drop할 수 있습니다. 해당 assertion을 제거하고 callback 시작 순서,
diagnostics 종료 상태, fatal counter처럼 계약을 직접 검증하는 조건만 남겼습니다.
같은 이유로 executor rejection 회귀 테스트도 best-effort observer callback을 완료
신호로 사용하지 않고 `snapshot()`의 `admitted == 0`과
`executorRejections == 1` 상태를 기다리도록 정리했습니다.

## 향후 지침

bounded diagnostics를 수정할 때 queue depth, slot active 상태, callback
reservation을 서로 다른 lock 또는 CAS 단계로 분리하지 않습니다. queue poll을
테스트에서 멈출 수 있는 deterministic barrier를 유지해 observer close가
reservation 전후 어느 쪽으로 선형화되는지 검증하고, close/fatal/inactive 경로의
permit 반환을 각각 한 번만 확인합니다.

## Writer DoD

- SPW-01: Issue #732, `BoundedLeaderAuditExporter`, diagnostics admission 계약과
  현재 소스·회귀 테스트를 근거로 audience와 범위를 고정했습니다.
- SPW-02: 맥락·원인·결정·결과·검증·놀라움·향후 지침을 모두 기록했습니다.
- SPW-03: 한국어 기술 문체와 기존 `diagnostics`, `reservation`, `permit`,
  `callback`, `gate` 용어를 유지하고 자연스러움 검토를 완료했습니다.
- SPW-04: Issue acceptance와 source diff, RED/GREEN 출력의 식별자·수치를
  대조했습니다.
- SPW-05: 최종 Markdown을 다시 읽었고 headings와 code tokens를 확인했습니다.
