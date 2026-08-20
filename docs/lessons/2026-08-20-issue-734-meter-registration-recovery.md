# Issue #734 Micrometer meter 등록 복구와 scrape 비교 교훈

## Context

AUD-02 Micrometer decorator는 하나의 `MeterRegistry`에 13개 고정 meter ID를
등록하고 close/replacement 세대에서도 meter identity와 cumulative 값을 유지한다.
이번 slice의 목표는 등록 도중 일부 meter만 성공한 뒤 재시도하는 경계를 고정하고,
scrape hot path에서 cumulative 비교를 위해 임시 collection을 만들지 않는 것이다.

## Root Cause

manager는 부분 등록 뒤에도 이미 등록한 소유 meter가 남아 있는 상태를 보존하지만,
custom failing `MeterRegistry`를 사용하는 회귀 테스트가 없어 다음 acquire가 누락된
ID만 채우는지, 기존 identity를 유지하는지, foreign meter를 제거하지 않는지 증거가
없었다. 또한 `CumulativeValues.isNotLessThan`이 11개 비교 결과를 `listOf(...).all`
로 묶어 scrape마다 Boolean list를 만들고 있었다.

## Decision

- 부분 등록 실패 뒤 manager-owned meter와 identity를 보존하고 다음 acquire에서
  누락된 fixed ID만 등록하는 계약을 custom registry fixture로 고정한다.
- fixed catalog가 13개인지, duplicate registration과 `MeterRegistry.remove`가
  발생하지 않는지 함께 검증해 ownership 경계를 보존한다.
- cumulative 비교는 각 scalar 조건을 `&&`로 직접 연결해 allocation-free 경로로
  유지한다.
- 동작 계약은 `MicrometerLeaderAuditExporter` KDoc과 EN/KO README에 같은 의미로
  기록한다.

## Outcome

부분 등록 복구는 기존 manager 상태를 재사용하면서 누락된 meter만 보충하고 foreign
meter를 건드리지 않는다. scrape 비교는 임시 Boolean collection 없이 11개 누적
필드를 scalar short-circuit로 비교한다.

## Verification

- RED: 기존 `listOf(...).all` 구현에서 allocation-free source contract가 실패했다.
- focused `MicrometerLeaderAuditExporterTest`: 23 tests PASS.
- `:bluetape4k-leader-micrometer:test`: 105 tests PASS.
- `:bluetape4k-leader-micrometer:detekt`: PASS.
- `git diff --check`: PASS.

## Surprise and miss

부분 등록 동작 자체는 이미 manager의 `ensureMeters()`에 구현되어 있었고, 이번 결함은
재시도·identity·foreign removal을 한 번에 입증하는 custom registry 회귀 증거가 없었던
점이었다. source contract 테스트는 모듈 worktree 기준 상대 경로를 사용해야 하므로
테스트 실행 위치와 파일 경계를 함께 고정했다.

## Future guard

새 fixed meter를 추가하거나 registration lifecycle을 바꿀 때는 부분 실패 지점과
재시도 후 meter identity를 같은 custom registry fixture에서 확인한다. scrape hot path의
누적 비교·tag projection에는 임시 collection을 추가하지 않고, allocation-free 요구가
변경되면 source contract와 benchmark 또는 profiler 근거를 함께 갱신한다.
