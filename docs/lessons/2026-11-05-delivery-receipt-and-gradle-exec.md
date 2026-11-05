# Gradle 검증 task와 delivery receipt의 상태 경계를 먼저 확인한다

## 맥락

Issue #997~#1000 후속 변경을 `fix/leader-followups-1000`에서 검증하고 PR delivery를 준비했다. 변경에는 backend facade consumer-bytecode verifier를 Gradle gate와 CI에 연결하는 작업이 포함되어 있었다.

## 발견과 원인

첫 `verifyIssue994FacadeContracts` 실행은 `build.gradle.kts`의 `doLast` 안에서 `exec {}`와 `commandLine(...)`을 호출하면서 `Unresolved reference 'exec'` 및 `Unresolved reference 'commandLine'`으로 실패했다. `doLast`의 receiver는 `Task`이므로 Project의 `exec` DSL을 암시적으로 사용할 수 없었다.

동일한 delivery receipt에서 `lane-create`를 실행할 때는 `--expected-head`에 Git commit SHA를 전달했다. 이 인자는 repository HEAD가 아니라 직전 receipt event의 lowercase SHA-256 checksum을 요구하므로 `expected receipt head must be lowercase SHA-256` 계약 오류가 발생했다.

첫 PR CI의 `Validate CI fan-out contract`는 로컬에서 통과한 동일한 verifier를 `fetch-depth: 1`인 shallow merge ref에서 실행하면서 실패했다. 역사적 baseline `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`의 source를 `git show`로 읽어야 하지만 해당 commit 객체가 checkout에 없어 `exists on disk, but not in 'ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f'` 오류가 발생했다.

## 결정

- 여러 Python verifier 모드는 각 `Exec` task로 등록하고, 각 task가 backend `testClasses`에 의존하도록 구성한다. `doLast` 안에서 Project API를 암시적으로 호출하지 않는다.
- receipt mutation 전에는 `completion-check` 또는 직전 mutation 결과에서 현재 receipt checksum을 읽고, 다음 명령의 `--expected-head`에만 사용한다. Git HEAD는 별도 exact-head 증거로 기록한다.
- verifier가 보호하는 source declaration 비교에서는 reader-facing KDoc을 구현 변경과 분리한다. KDoc은 비교에서 제외하되, modifier·signature·overload·consumer bytecode 검사는 유지한다.
- 역사적 Git baseline을 읽는 CI verifier job은 `actions/checkout`에 `fetch-depth: 0`을 명시해 shallow merge ref에 의존하지 않도록 한다.

## 결과와 검증

- `verifyIssue994FacadeContracts`가 source inventory, verifier self-test, 68개 Kotlin consumer bytecode callsite를 모두 통과했다.
- PR #1001의 첫 hosted CI에서 shallow checkout 원인을 확인했고, `ci-contract` checkout을 full history로 고친 뒤 동일 검증을 재실행했다.
- `ZooKeeperAsyncExecutorTest`를 `--rerun-tasks`로 실행해 10/10 테스트가 통과했다.
- `detekt`, `checkBinaryCompatibility`, `actionlint`, CI fan-out/release provenance 검증이 통과했고 미분류 공개 ABI incompatibility는 0건이었다.
- delivery receipt `20261105T033819Z-9057e4d3`는 lane·check·component 증거를 기록한 뒤 exact completion으로 닫는다.

## 재발 방지

Gradle 검증 task를 추가할 때는 task type과 receiver를 먼저 고정하고, 여러 외부 명령은 별도 `Exec` task 또는 명시적인 Project API로 등록한 뒤 최소 configuration 단계에서 컴파일한다. 역사적 baseline을 사용하는 CI job에는 `fetch-depth: 0`을 명시하고, shallow checkout을 재현하는 hosted check를 확인한다. receipt mutation 명령은 Git ref와 receipt checksum을 혼동하지 않도록 직전 JSON 결과의 `checksum`을 다음 `--expected-head`로 전달한다. PR 전에는 `git diff --check`, exact local HEAD, receipt replay, 그리고 외부 side effect 직전의 issue/branch metadata를 각각 새로 읽는다.

## 작성·검토 DoD

| 항목 | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | 대상은 delivery workflow와 Gradle verifier 유지보수이며, 두 실제 오류와 관련 source/receipt를 고정했다. |
| SPW-02 | PASS | 맥락·원인·결정·결과·검증·재발 방지 구조를 갖췄다. |
| SPW-03 | PASS | 한국어 기술 문장으로 작성하고 commands, identifiers, exact errors를 보존했다. |
| SPW-04 | PASS | `build.gradle.kts`, `verify_issue_994_facades.py`, receipt checksum과 fresh command 결과를 대조했다. |
| SPW-05 | PASS | 최종 Markdown을 다시 읽고 heading, table, inline code, technical flow를 확인했다. |
| KO-01–KO-07 | PASS | 사실·오류·용어·register·reader-facing surface를 확인하고 terminology audit을 실행한다. |
