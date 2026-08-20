# Micrometer weak registry retirement와 close 예외 순서

## Context

Issue #735는 AUD-02 Micrometer decorator의 P2 lifecycle 후속 작업이다. #734
stacked slice의 기준 구현에는 `RegistryManagerStore`의 weak identity key와
`ReferenceQueue` drain, 그리고 `close()`의 `DETACHED` 전환이 이미 들어가
있었다. 이번 작업의 목적은 이 계약을 GC timing이나 단일 실패 fixture에
의존하지 않고 회귀 테스트로 고정하는 것이다.

## Root cause와 놓친 증거

구현 자체보다 증거가 부족했다. 기존 테스트는 registry가 회수된 뒤 stale
manager가 다음 `acquire()`에서 제거되는지, manager가 `MeterRegistry`를 직접
strong-reference하지 않는지 관찰하지 않았다. 또한 close-entry 기준 데이터,
`delegate.close()`, final 기준 데이터를 서로 다른 예외로 실패시키면서
`primary`와 `suppressed` chain을 확인하지 않았다.

Issue 본문의 호출 순서 표기는 시간 순서로 읽을 수 있지만, canonical
AUD-02 설계의 예외 계약은 `delegate.close` → final 기준 데이터/invariant →
transition/diagnostic 순서다. 따라서 `delegate.close` 예외가 primary이고,
final 기준 데이터와 close-entry 예외가 그 순서로 suppressed가 되어야 한다.

## Decision

- private `RegistryManagerStore`를 reflection으로 읽는 deterministic fixture를
  사용한다. `WeakIdentityKey`를 직접 `clear()`/`enqueue()`한 뒤 새 registry를
  `acquire()`해 queue drain을 관찰하므로 실제 GC 스케줄에 의존하지 않는다.
- manager의 `registryReference`가 `WeakReference`이고 직접적인
  `MeterRegistry` field가 없음을 확인한다.
- `SnapshotExporter`에 독립 기준 데이터 script와 close failure 주입을 추가한다.
  8개 close failure 조합을 실행해 primary identity, suppressed 순서,
  delegate exact-once close, `diagnosticsClosed`, same-registry replacement을
  함께 검증한다.
- canonical 설계와 이미 일치하는 production 구현은 변경하지 않는다. 회귀
  감지력을 확인하기 위해 queue drain을 제거하거나 close aggregation 순서를
  앞당긴 임시 mutation에서 각 테스트가 실패하는 것을 확인한 뒤 원복했다.

## Outcome

`MicrometerLeaderAuditExporterTest`에 weak registry retirement, 전체 close
failure matrix, simultaneous failure chain 테스트를 추가했다. 최종 source
diff는 test fixture와 이 lesson으로 제한되며 public API와 runtime behavior는
변경하지 않는다.

## Verification

- 기준 focused suite: 기존 23개 테스트 통과
- 최종 focused suite: `MicrometerLeaderAuditExporterTest` 26개 통과
- queue drain 제거 mutation: retirement test 1개 실패
- close aggregation 순서 mutation: failure matrix 실패
- 최종 module test, detekt, `git diff --check` 결과는 workflow receipt와
  최종 DoD 보고서에 기록한다.

## Future guard

Micrometer lifecycle 변경은 실제 GC를 기다리는 fixture 대신 weak key를
deterministically enqueue하고 다음 observation point에서 stale entry가
제거되는지 증명해야 한다. close failure 테스트는 세 단계에 서로 다른
`Throwable` identity를 주입하고 `primary`/`suppressed` 순서를 명시적으로
검증해야 하며, 예외가 발생해도 `DETACHED`, delegate release,
replacement acquire를 함께 확인해야 한다. Issue 본문과 canonical 설계의
예외 순서가 다르게 읽히면 어느 한쪽만 조용히 바꾸지 말고 설계 계약부터
갱신한다.

## Sources

- Issue #735: https://github.com/bluetape4k/bluetape4k-leader/issues/735
- AUD-02 design: `docs/superpowers/specs/2026-08-18-issue-535-audit-export-design.md`
- AUD-02 plan: `docs/superpowers/plans/2026-08-18-issue-535-audit-export-plan.md`
