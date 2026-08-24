# Issue #766 diagnostics probe base 설계 통합 review

## 검토 대상과 판정

- 대상: `docs/superpowers/specs/2026-08-24-issue-766-diagnostics-probe-base-design.md`
- 검토 기준 commit: `8c20c8f6`
- Issue: [#766](https://github.com/bluetape4k/bluetape4k-leader/issues/766)
- 후속 운영 Issue: [#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)
- 현재 상태: 구현 전 설계 검토
- 통합 판정: **PASS**
- 심각도 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`

이번 판정은 구현·빌드 완료 판정이 아니다. 기존 `leader-core` baseline은
`./gradlew :bluetape4k-leader-core:test --no-daemon --no-configuration-cache
--max-workers=1 --console=plain`에서 840 tests, failures/errors/skips 0으로
확인했지만, 새 변경은 아직 구현하지 않았다.

## 검토 관점별 결과

| 관점 | 판정 | 확인한 경계 |
|---|---|---|
| Performance | PASS | 단일 `checkedAt`, callback 1회, caller-thread 동기 실행, retry·executor·새 I/O 없음 |
| Stability | PASS | timeout 선검증, `Clock` 실패 전파, cancellation/interruption/Error 보존, interrupt flag cleanup, stateless helper |
| Security | PASS | raw exception·credential·endpoint 미저장, `Error` 재전파, custom override와 adapter의 정보 경계 |
| Operations | PASS | Ktor HTTP와 Spring warning/detail 차이를 표로 고정, `UNKNOWN` readiness 비보장, #774 관측/runbook 후속, 출시 후 ABI 보존 rollback |
| Developer/API | PASS | Kotlin/JVM 공개 API 범위, Java facade 후속, `NOT_CHECKED` built-in/custom 경계, ABI·consumer smoke 증거 |
| Caller/User | PASS | built-in/custom direct/Ktor/Spring 결과표, 예외별 warning·HTTP·detail·interrupt 기대값, root/module README 대상 고정 |

## 이전 finding과 처분

1. Ktor built-in 일반 예외가 기존 pipeline 실패에서 `HTTP 200 + UNKNOWN`으로
   바뀌고 Spring built-in normalized result가 warning 없이 allow-listed detail을
   반환할 수 있다는 점을 adapter 표·acceptance·회귀 테스트에 명시했다.
2. custom `checkConnectivity()`/`diagnostics()` override는 source 호환성 때문에
   허용하되 helper가 결과를 재작성하지 않는 legacy escape hatch로 고정했다.
   Ktor는 application pipeline 정책을 따르고 Spring은 기존 catch 경계를 따르며,
   custom `NOT_CHECKED` 반환과 built-in invalid `NOT_CHECKED` 예외를 분리했다.
3. `UNKNOWN` 원인별 관측, readiness·경보, provider-native timeout runbook은 새
   public API 이슈와 중복하지 않도록 #774가 소유한다. #766은 helper·provider
   migration·adapter 회귀·ABI/consumer evidence를 소유한다.
4. versioned `docs/manual`은 현재 `manifest.yaml`의 0.5.0 release pin과 새 API가
   불일치하므로 #766에서 갱신하지 않는다. #774의 1.0.0 release-train에서
   releaseRef/releaseCommit을 새 API 포함 commit으로 고정한 뒤 EN/KO manual을
   갱신한다.
5. 출시 전 전체 revert와 출시 후 public helper ABI 유지·provider migration만
   corrective release로 되돌리는 절차를 분리했다. `checkBinaryCompatibility`
   외에 Kotlin consumer compile smoke, `jar tf`, `javap`를 계획에 포함한다.
6. interruption 테스트는 pre-clear, 전용 thread의 `join(timeout)`/종료 확인 또는
   `finally` cleanup을 사용하고, clock read→callback 순서와 callback 1회 실행을
   증명하도록 보강했다.

## 검증된 문서 품질

- `git diff --check`: PASS
- `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/superpowers/specs/2026-08-24-issue-766-diagnostics-probe-base-design.md`: findings 0
- SPW-01~SPW-05: PASS
- KO-01~KO-07: PASS
- live Issue #766: assignee `debop`, milestone `1.0.0`, labels `enhancement`,
  `feature`, `design`, `integration`; 설계 보완·소유권 정리 comment readback 완료
- live Issue #774: `debop`, milestone `1.0.0`; 관측성·readiness·manual/runbook만
  소유하도록 body readback 완료

## 구현 착수 조건

이 설계 review는 구현 착수 조건을 충족한다. 다음 게이트는 이 문서를 기준으로
구현 plan을 작성하고, plan의 6-perspective review에서 P0/P1을 0건으로 만든 뒤
사용자 승인 후 TDD 구현을 시작하는 것이다. PR 생성·merge 권한은 이 review에
포함되지 않는다.
