# Issue #559(OBS-02) 문서 closeout lesson

## Context

Epic #699의 OBS-02 구현 train은 `leader-core`, `leader-micrometer`,
`leader-spring-boot`에 이미 반영되어 있었다. 그러나 공개 README에는 issue #559가
아직 미완성으로 남아 있었고, core observer 계약·Micrometer 매핑·Spring lifecycle을
처음 읽는 사용자가 한곳에서 확인할 수 없었다. `docs/manual/manifest.yaml`은
`0.5.0`/`721a9a3808f67489d2bdb8177734325981c24977`에 고정되어 있으므로 새 API를
release manual에 섞을 수 없었다.

## Decision

- Root, `leader-core`, `leader-micrometer`, `leader-spring-boot`의 EN/KO README를
  같은 계약과 수치로 갱신한다.
- 새 API는 고정 manual을 수정하지 않고 `status: unreleased` EN/KO draft에 기록한다.
- Draft에는 source commit, event/outcome 계약, blocking·suspend·watchdog parity,
  bounded in-flight admission, privacy, Micrometer, Spring lifecycle, promotion gate를 함께
  적어 release 경계를 눈에 띄게 유지한다.
- Prometheus dashboard example은 `context.name?.startsWith("leader.")` predicate로
  `bluetape4k.leader.lease.extension`을 자동 로그하지 않으므로, 이 closeout에서
  실제로 지원하지 않는 app behavior를 주장하지 않는다. App-owned handler가 exact
  observation name을 명시적으로 opt-in할 수 있다는 점만 후속 선택지로 남긴다.

## Outcome

README의 stale #559 문장을 현재 observer 사용법으로 교체하고 EN/KO 쌍에 동일한
API 이름, outcome 목록, in-flight admission 한도, close semantics, redaction 경계를 기록했다.
Micrometer README에는 observation 이름과 `Extended`/`Rejected`/`NotHeld`/
`WrongThread`/`BackendError` 매핑을 추가했고, Spring README에는 registry identity별
공유와 마지막 context close 규칙을 추가했다. Core `BackendError.cause`가 원본
예외로 남고 custom observer가 별도 sanitise해야 한다는 경계를 README와 draft에
추가했으며, Spring/Micrometer 문서에는 `includeExceptionDetails`가 tag sanitiser를
우회한다는 운영 주의를 명시했다. Draft는 pinned release의 기능으로 오인되지
않도록 frontmatter와 경고를 갖춘 상태다. 명시적 호출의 active scope를
`@LeaderElection`, `@LeaderGroupElection`, direct elector body까지 넓혀 기록했고,
`WATCHDOG`는 `autoExtend = true` 단일 리더에만 발생하며 group auto-extension은
비활성이라는 경계를 고정했다. `Rejected`(watchdog reservation 또는 user bounded
operation queue)와 registry delivery 전용 `droppedCount()`를 분리했다. User queue가
timeout되어 `Rejected`를 반환해도 이미 큐에 들어간 명령이 나중에 실행될 수 있고
`elapsedNanos`는 caller 측 delegate 호출 시간을 담을 수 있다는 점도 명시했다.
Fail-open의 `context.lockName`/`auditLeaderId = null`, delegate가 반환한
`BackendError`와 delegate가 던진 `Exception`의 차이도 기록했다.
예제는 blocking 상세 API용임을 분명히 하고 suspend active scope에서는
`extendActiveLockDetailedSuspend(...)`를 호출하도록 EN/KO 표면을 맞췄다.

독립 성능·안정성·보안·운영·API·사용자 리뷰에서 처음 발견한 P1/P2/P3 문서 오기는
모두 보수했다. 재검토 결과 각 관점은 `APPROVE`, `P0=0/P1=0/P2=0/P3=0`으로
수렴했으며 통합 review artifact에 근거와 후속 위험을 기록한다.

## Verification

- 변경 전 exact `origin/develop`(`d15d4d948cfe4b371ffecacecb19e8cf45b8f384`)에서
  core 90개, Micrometer 5개, Spring 22개 targeted test가 통과했다.
- 변경 후 core targeted 90개(`SUCCESS: Executed 90 tests in 8.3s`), Micrometer
  targeted 5개(`SUCCESS: Executed 5 tests in 1.2s`), Spring targeted 22개
  (`SUCCESS: Executed 22 tests in 6.6s`)가 모두 통과했다.
- `:bluetape4k-leader-core:detekt`, `:bluetape4k-leader-micrometer:detekt`,
  `:bluetape4k-leader-spring-boot:detekt`가 `BUILD SUCCESSFUL`이다.
- `git diff --check`, README 언어 전환 검사(`groups=37; files=74; failures=0`),
  변경 Markdown 상대 링크 검사(`files=13; failures=0`), stale marker scan,
  EN/KO contract-token·code-block parity가 통과했다.
- `ruby scripts/manual/release_inventory.rb ... 35`와 두 manual validator가
  `Manuals are aligned`, `442 checked, 0 missing`을 반환했고 Ruby manual test는
  `37 runs, 392 assertions, 0 failures, 0 errors, 0 skips`였다. Korean terminology
  audit는 8개 파일, `findings=0`이다. 검증은 전체 inventory가 아니라 release 범위로
  생성한 `build/manual/release-module-inventory.json`을 입력으로 사용했다.

## Surprise and recovery

Receipt가 없는 상태에서 먼저 실행한 `mutation-check`은 coordinator conflict로
실패했다. 이는 문서 자체의 오류가 아니라 workflow receipt 초기화 전 상태 점검
순서 문제였다. `bluetape-flow.py init`으로 owner와 run을 만들고 `run-approve`,
`run-start`, topology registration, startup acknowledgement를 순서대로 완료한
뒤 같은 mutation gate를 다시 통과시켰다. 이후 작업은 canonical `develop`와
분리된 `docs/issue-559-obs02` worktree에서 수행하며, canonical checkout의 기존
untracked `workflow-inputs/`는 건드리지 않는다. 첫 manual validation에서 full
inventory를 직접 넘겨 `examples: missing from manifest`를 확인했지만, 이는
examples를 제외하는 release validator의 입력 계약을 어긴 호출이었다. Release
inventory를 먼저 생성한 뒤 validation을 재실행해 `Manuals are aligned`와
`Release manuals are compatible ...: 442 checked, 0 missing`을 확인했다. 새 draft의
source 링크도 pinned release commit에 존재하지 않는 경로를 가리키지 않도록
`develop` GitHub 링크로 바꾸어 같은 release 검증을 통과시켰다.

## Future guard

새 public observer/event 계약을 추가할 때는 다음 네 산출물을 같은 변경에서 갱신한다.

1. Root와 영향받은 module의 EN/KO README pair.
2. 현재 release manifest 경계를 확인한 release manual 또는 unreleased draft pair.
3. 구현 source와 문서 claim을 대조한 Korean lesson/review artifact.
4. Core·adapter·lifecycle targeted test와 stale/link/parity 검증 evidence.

특히 observation 이름이나 handler predicate를 추측해 example의 자동 동작으로
확대하지 말고, 실제 source와 test에서 확인된 범위만 문서화한다.
