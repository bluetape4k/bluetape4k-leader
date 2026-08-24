# Issue #766 diagnostics probe base 구현계획 통합 review

## 검토 대상과 판정

- 대상: `docs/superpowers/plans/2026-08-24-issue-766-diagnostics-probe-base-plan.md`
- 검토 기준 commit: `56172c46`
- Issue: [#766](https://github.com/bluetape4k/bluetape4k-leader/issues/766)
- 후속 운영 Issue: [#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)
- 현재 상태: 구현 전 계획 검토
- 통합 판정: **PASS**
- 심각도 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`

이번 판정은 구현·빌드·PR·merge 완료 판정이 아니다. 구현은 이 계획의
승인 게이트와 TDD 순서를 따른다.

## 6개 관점 결과

| 관점 | 판정 | 확인한 경계 |
|---|---|---|
| Performance | PASS | 단일 `checkedAt`, callback 1회, caller thread 동기 실행, 추가 I/O·retry·executor·deadline 없음, 8×4 고유 동시성 fixture |
| Stability | PASS | 양수 유한 timeout 선검증, clock/예외 경계, interrupt flag cleanup, legacy override, affected-module `cleanTest`와 XML fail-fast, bounded consumer cleanup |
| Security | PASS | built-in helper/Spring allow-list의 raw exception·credential·endpoint 비직렬화와 custom descriptor/Ktor pipeline caller-owned sanitization 분리, endpoint 보호와 #774 운영 경계 |
| Operations | PASS | Ktor no-plugin cause-chain identity, Spring `OutputCaptureExtension` 정확 warning, receipt `--state-root`/head 갱신, pre/post-release rollback과 문서 동시 복원 |
| Developer/API | PASS | `requireGt` 반환값 보존, public helper JVM value-class ABI, `checkConnectivity`/`diagnostics` legacy override source·dispatch 호환, Kotlin consumer smoke |
| Caller/User | PASS | direct/Ktor/Spring 예외·validation·`NOT_CHECKED` 결과 fact matrix, EN/KO parity read-back, custom caller 책임과 #774 소유권 분리 |

## 이전 finding과 처분

1. `requireGt` 반환값을 callback에 전달하지 않던 계획은 검증된 `Duration`을
   `probe(validTimeout)`에 넘기는 형태로 수정했다.
2. 동시성·순서·caller-thread 증거를 고유 `Instant`, 교차 `UP`/`DOWN`, exact
   timeout, 8×4 결과 집합으로 고정했다.
3. Ktor fatal/validation 사례는 `StatusPages` 의존성 없이
   `client.get` cause-chain identity를 확인하고, Spring warning은 기존
   `leader.spring.health backend probe failed; status=UNKNOWN`를
   `OutputCaptureExtension`으로 검증한다.
4. affected 8개 모듈의 `cleanTest` 후 XML만 읽고 `set -eu`로 누락·skip을
   fail-fast한다. consumer smoke는 core jar 생성·단일 경로 확인과 trap 기반
   bounded cleanup을 사용한다.
5. Kotlin `Duration` value-class lowering에 맞춰 `INSTANCE`와 mangled
   `check-...(long, Clock, Function1)`을 독립 `rg -q` 조건으로 검증한다.
6. custom `checkConnectivity()`와 `diagnostics()` override는 direct matrix와
   외부 consumer source compile로 각각 검증하며, base helper의 built-in
   정규화와 legacy escape hatch를 혼동하지 않는다.
7. EN/KO 문서에는 timeout, callback/clock/validation, adapter별 예외, custom
   sanitization, #774 후속 범위를 fact matrix로 read-back한다. versioned
   manual은 현재 `0.5.0` release pin 때문에 제외한다.
8. 출시 전 rollback은 전체 commit 역순이고, 출시 후 corrective release는
   public helper ABI/core tests를 보존하면서 provider·adapter 기대값·README·
   한국어 release note를 함께 복원하도록 고정했다.

## 검증된 문서 품질

- `git diff --check`: PASS
- `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/superpowers/plans/2026-08-24-issue-766-diagnostics-probe-base-plan.md`: findings 0
- unfinished-marker/error-handling scan: 0
- SPW 문서 검토: 구조·실행 순서·검증 명령·Korean terminology PASS
- live Issue #766: `debop`, milestone `1.0.0`, labels `enhancement`, `feature`, `design`, `integration`; base contract와 adapter/ABI 범위 readback 완료
- live Issue #774: `debop`, milestone `1.0.0`; 원인 신호·readiness·경보·runbook·versioned manual 소유권 readback 완료
- 기존 `leader-core` baseline: 840 tests, failures/errors/skips 0

## 구현 착수 조건

계획 review는 구현 착수 조건을 충족한다. 다음 순서는 사용자 승인 이후
`$test-driven-development`를 다시 읽고 RED 테스트부터 시작하는 것이다.
Type A receipt에는 각 component의 `plan` check를 최신 checksum으로 순차
기록한다. 구현·tests·ABI·consumer·docs·lesson·component evidence가 모두
완료되기 전에는 `DONE`으로 보고하지 않는다.

이 review는 PR 생성이나 merge 권한을 부여하지 않는다.
