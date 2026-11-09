# Issue #1003 교훈 — Full Nightly fail-closed 계약 (2026-11-09)

## 맥락

Issue #1003 검토에서 Full Nightly가 `needs.*.result`에 `skipped`가 포함되어도
`failure`와 `cancelled`만 거부하는 것을 재현했다. 같은 실행의 `coverage-report`도
`aggregate-kover-coverage.py`에 기대 artifact 목록을 전달하지 않아 일부 coverage
artifact가 누락되어도 집계를 통과시킬 수 있었다.

## 결정

- `scripts/ci/validate_nightly_gate.py`를 기준 판정기로 두고, Full scope에서는
  모든 필수 job 결과가 `success`여야 하며 Redis Cluster도 `success`여야 한다.
- Daily smoke scope에서는 기존 계약을 보존해 `skipped`를 허용하고 `failure`와
  `cancelled`만 거부한다.
- `nightly-status`는 별도 runner에서 실행되므로 `actions/checkout@v7`와
  `actions/setup-python@v7`를 먼저 실행한다.
- Full Nightly coverage 집계에는 현재 `Upload coverage report`가 만드는 22개
  artifact를 모두 `--expected-artifact`로 전달한다.
- Kover 계약 검증기가 workflow의 coverage upload 이름과 기대 목록을 대조하고,
  producer가 0개이거나 중복되는 경우와 `nightly-status`의 판정기 호출도 검사한다.
- Full Nightly 결과에 빈 상태 토큰이 섞이면 판정기가 실패한다.
- Full Nightly 실행 `42502855181`에서 exact head
  `33811400d2750dc04052f07166632bb27abb7969`의 MongoDB/Spring Boot job이
  `mongo:8.0.28`의 `waiting for connections` 대기 timeout으로 종료했다. 당시
  `ubuntu-24.04` runner의 커널이 `7.0.0`으로 갱신되어 MongoDB 8.0의 커널 호환성
  제한을 다시 밟은 것이 원인이므로, CI에 이미 적용한 `ubuntu-22.04` pin을 Nightly와
  examples의 MongoDB 민감 job에도 동일하게 적용한다.

## 결과

필수 job의 `skipped`와 부분 coverage artifact를 모두 성공으로 오인하는 두 fail-open
경로를 로컬 계약 테스트에서 재현하고 차단할 수 있게 되었다. CI 계약 job도 판정기
회귀 테스트를 실행한다.

runner pin을 `ubuntu-22.04`로 맞춘 뒤 [Full Nightly 실행 `42549454066`](https://github.com/bluetape4k/bluetape4k-leader/actions/runs/42549454066)은
exact head `c6864ebaa43bea87c49e743e6bd76e904ffe0a6e`에서 MongoDB/Spring Boot를
포함한 모든 필수 결과를 `success`로 완료했다. `coverage-mongodb`와
`coverage-spring-boot`를 포함한 22개 coverage artifact가 생성되었고,
`Coverage Report`와 `Nightly Status`도 성공했다.

## 검증

- `scripts/ci/validate_nightly_gate_test.py`: Full scope의
  `success,skipped,success`를 거부하고 smoke scope에서는 허용한다.
- `scripts/ci/validate_kover_contract_test.py`: 기대 artifact 목록과 fail-closed
  판정기 호출, producer 0개, `nightly-status` checkout 누락을 거부한다.
- `scripts/ci/validate_kover_contract.py --static`: 현재 `ci.yml`과
  `nightly-tests.yml`의 Kover/Full Nightly 계약을 통과한다.
- 외부 Full Nightly `42502855181`은 위 원인으로 fail-closed했다.
- runner pin 수정 후 `42549454066`을 새 exact head로 dispatch했고, Full scope의
  모든 job·coverage·Nightly Status가 성공했다.

## 놓친 점과 재발 방지

예약된 Daily smoke의 `skipped`는 선택 범위의 정상 결과일 수 있으므로 Full scope와
동일한 판정을 적용하지 않는다. 새 coverage job을 추가할 때는 upload 이름, Full
Nightly 기대 목록, 계약 테스트를 한 변경으로 갱신하고, Full Nightly 성공을 주장하기
전에 주간 Full 실행의 exact head와 job 결과를 다시 읽는다. GitHub runner label은
고정된 커널을 보장하지 않으므로, MongoDB 같은 호환성 민감 fixture를 사용하는
workflow는 이미지/커널 계약을 명시하고 CI·Nightly·examples의 pin 계약 테스트를
함께 유지한다.
