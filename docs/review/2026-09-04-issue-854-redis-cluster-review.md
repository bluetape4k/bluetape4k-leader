# Issue #854 Redis Cluster 지원 7-Tier 코드 리뷰

## DoD 판정

- 대상: `origin/develop` `c47324bcff5738e5505cf733d838afd730bad226`
- 변경 branch/worktree: `feat/issue-854-redis-cluster`
- 이슈: [#854](https://github.com/bluetape4k/bluetape4k-leader/issues/854), milestone `1.1.0`
- 리뷰 모델: `gpt-5.6-luna`, effort `max`
- 로컬 판정: **P0=0, P1=0, P2=4, P3=1**
- 전달 판정: **PENDING** — hosted Nightly/PR/remote CI와 merge는 이 실행에서 권한을 사용하지 않았다.

P1은 모두 구현 또는 검증으로 수렴했다. 독립 최종 재검토에서 지적된 stale legacy-index 정리,
하위 우선순위 malformed source 표면화, Cluster matrix 범위, no-test guard는 이번 inline
수정과 회귀 테스트로 해소했다. 다만 로컬 Testcontainers가 hosted Nightly를 대신하지 않으며,
failover 성능 benchmark는 이슈의 비범위다.

## 7-Tier 결과

| Tier | 판단 및 근거 | 상태 |
|---|---|---|
| 1. Intent/Contract | Issue #854의 Cluster 지원, same-slot, migration/TTL, blocking/suspend parity 범위를 유지했다. 정상 contention은 기존 `null`/skip 계약을 바꾸지 않는다. | PASS |
| 2. Architecture/API | `LettuceCandidateCommands.kt:15-92`, `LettuceSuspendCandidateCommands.kt:15-90`이 direct/script capability와 standalone/Cluster value reader를 분리한다. 네 elector의 `@JvmOverloads` standalone constructor와 additive Cluster constructor를 `...Strategic*Elector.kt:27-48`에서 확인했다. | PASS |
| 3. Data/Redis | `LettuceCandidateKeyCodec.kt:23-58`의 v3 `{lengthDelimited(lockName)}` tag가 index/candidate/tombstone/token을 한 slot으로 묶는다. `LettuceCandidateWriteScript.kt:31-101`은 v3 key만 Lua `KEYS`에 받고 legacy source는 ARGV로만 전달한다. 실제 Cluster에서 `MGET`·Lua가 `CROSSSLOT` 없이 완료됐다. | PASS |
| 4. Concurrency/Lifecycle | `LettuceCandidateRegistry.kt:121-137, 262-314`와 suspend 대응 코드가 persistent tombstone, `SET NX` migration token, raw+token compare cleanup을 적용한다. write-script barrier/token 테스트와 persistent source `PTTL=-1` 회귀 테스트가 통과했다. | PASS |
| 5. Tests/CI | `build.gradle.kts:35-132`의 `clusterTest`는 test classes/classpath, tag 격리, `failOnNoDiscoveredTests`, 직렬 실행, checked-in 17개 manifest exact-name·XML·provenance guard를 갖는다. Nightly job/aggregator는 `.github/workflows/nightly-tests.yml:203-276,1294-1345`에 연결됐다. | 로컬 PASS; hosted PENDING |
| 6. Security/Operations | `{}` lock-name 거부, caller-owned connection, `MOVED`/`ASK` topology 경계, v3 write 이후 old binary rollback 금지와 forward-fix 절차를 README/spec에 명시했다. connection/client/container를 registry가 닫지 않는다. | PASS |
| 7. Docs/ABI/Release | README 양국어가 `7.6.0.RELEASE`, v3 migration, rollback 경계를 반영한다. custom ABI checker는 `artifacts=16 ignored=1 unknown=0`; Gradle `checkBinaryCompatibility`와 Kotlin/Java consumer compile이 통과했다. PR/push/merge/release는 실행하지 않았다. | 로컬 PASS; 전달 PENDING |

## 주요 findings와 disposition

| Severity | finding | disposition/evidence |
|---|---|---|
| P1 → 해소 | catalog가 7.7 주장과 실제 7.6 resolve가 어긋날 위험 | plan/spec/README를 `7.6.0.RELEASE`와 immutable ref `850959d0ea5f76ac7e2c442400f47653d5f95eed`로 고정하고 `dependencyInsight`로 재검증했다. |
| P1 → 해소 | Kotlin default constructor synthetic ABI 삭제 위험 | 기존 `(StatefulRedisConnection,String,int,DefaultConstructorMarker)` descriptor를 `@JvmOverloads`로 보존했다. old Kotlin fixture가 synthetic call을 compile했고, new Java fixture가 Cluster two-arg constructor를 compile했다. |
| P1 → 해소 | unregister와 migration resurrection race | same-slot persistent tombstone을 먼저 세우는 `UNREGISTER`, tombstone을 거부하는 `MIGRATE`, barrier 테스트로 순서를 고정했다. |
| P1 → 해소 | 같은 raw payload의 destructive cleanup race | migration 성공 때 unique token을 기록하고 `REMOVE_IF_VALUE`가 raw와 token을 모두 비교한다. refresh/result/register는 token을 지운다. |
| P1 → 해소 | Cluster task/CI false green | regular `test`에서 tag를 제외하고 `clusterTest`에 classpath/no-test/XML/skip guard 및 Nightly full-scope 판정을 추가했다. |
| P2 → 해소 | v3 index에 남은 stale legacy version member가 다른 source를 가렸다. | blocking/suspend `listCandidates`가 v2·colon payload를 모두 preflight하고, stale member가 속한 index만 정리한다. `LettuceCandidateKeyIsolationTest`에서 current v3와 stale legacy index 조합을 각각 검증했다. |
| P2 → 해소 | v3 payload가 유효해도 하위 우선순위 legacy source의 malformed payload가 short-circuit로 숨겨질 수 있었다. | 두 legacy version key를 모두 decode하고 malformed 예외를 표면화하도록 blocking/suspend lookup을 보정했다. 동일 회귀 테스트가 malformed lower-priority source를 고정한다. |
| P2 | source `GET`/`PTTL`은 서로 다른 slot의 legacy key를 원자적으로 snapshot하지 못한다. | 설계상 bounded race로 명시했다. source는 script에서 삭제하지 않고 post-copy 재확인+v3 token cleanup만 수행한다. 실제 Cluster race/failover stress는 별도 후속 검증이다. |
| P2 | `PTTL` 1ms/0/-2 및 runtime topology failover의 모든 조합은 hosted 환경에서 확인하지 않았다. | 코드가 `-1` persistent, `-2`/0 이하 만료 경계를 구분하고 positive TTL을 검증한다. hosted Nightly에서 확장 matrix를 실행해야 한다. |
| P2 → 해소 | Cluster integration이 public elector lifecycle을 충분히 실행하지 않을 위험 | `LettuceStrategicRedisClusterTest`를 blocking/suspend single/group의 register·refresh·result·unregister, v2/colon migration·positive TTL, group cancellation, direct script cancellation까지 **17개** 독립 test로 확장했다. `redis-cluster-test-matrix.txt` manifest의 exact method names를 Gradle/CI가 모두 확인한다. |
| P2 → 해소 | Cluster test scope가 count-only guard로 축소될 위험 | Gradle/Nightly가 checked-in manifest의 모든 testcase 이름 누락을 거부하고 `tests >= manifest`, `skipped=0`, `failures=0`, `errors=0`을 함께 단정한다. |
| P2 → 해소 | Cluster fixture provenance가 image/tag와 runtime topology를 충분히 남기지 않을 위험 | 성공 artifact에 image reference와 실제 `@sha256:` digest, fixture/helper, `cluster_state=ok`, 6개 endpoint, mapped ports를 기록하고 Gradle/Nightly가 필수 provenance를 검증한다. mutable tag pinning은 upstream fixture/hosted Nightly 후속 범위로 남긴다. |
| P2 | Cluster fixture provenance가 mutable `tommy351/redis-cluster:6.2` tag에만 의존한다. | 현재 실행은 실제 digest를 read-back해 증거화했지만 dependency/image reference 자체의 immutable pinning은 upstream fixture/hosted Nightly 후속 범위이므로 local PASS로 승격하지 않는다. |
| P2 | `MOVED`/`ASK` failover 수렴은 의도적으로 성능 benchmark 범위 밖이다. | caller-owned topology refresh와 외부 장애 경계를 문서화했다. 별도 benchmark 이슈가 필요하다. |
| P3 | test helper의 `!!`/reflection 의존 가능성은 운영 코드가 아닌 검증 보조 경로에 남아 있다. | 동작·ABI 검증을 막지 않는 low-risk 품질 항목으로 기록했다. production path에는 새 `!!`를 추가하지 않았다. |

## 검증 증거

| Check | 결과 |
|---|---|
| `:bluetape4k-leader-redis-lettuce:test` | 36 XML files, **352 tests, failures=0, skipped=0, errors=0** |
| `:bluetape4k-leader-redis-lettuce:clusterTest` | **17 tests, failures=0, skipped=0, errors=0**; manifest exact names 모두 관찰, image `tommy351/redis-cluster:6.2`, 실제 `@sha256:78eb164a6e3380b733cb3cfb91f7c54f50cba42292bcdc21b969450161af9e89`, `cluster_state=ok`, 6개 endpoint, fixture/helper·Colima Docker socket provenance 기록 |
| `koverXmlReport` | 생성 성공; INSTRUCTION `covered=11662/missed=2284`, BRANCH `covered=419/missed=239`, LINE `covered=1446/missed=246`, METHOD `covered=338/missed=67`, CLASS `covered=60/missed=10` |
| `detekt` | root task `BUILD SUCCESSFUL`; module adapter의 기존 18건을 수리 후 clean |
| ABI | `checkBinaryCompatibility` exit 0; custom `ABI inventory: artifacts=16 ignored=1 unknown=0` |
| Consumer fixture | old Kotlin one-arg/default call compile, new Java Cluster two-arg call compile; `javap`에서 old synthetic 및 new Cluster descriptors 확인 |
| Dependency | `io.lettuce:lettuce-core:7.6.0.RELEASE`, `dependencyInsight` exit 0 |
| Static contracts | CI fan-out, Kover fail-closed, assertion, Exposed provider validators 모두 exit 0; `check_binary_api_test.py` 16 tests OK; `actionlint`와 YAML parser OK |
| Regression | `LettuceCandidateLookupFailureTest` 8/8, `LettuceCandidateWriteScriptTest` 8/8, 전체 module 352/352 |

첫 combined module→Cluster 실행에서는 fixture의 `awaitClusterReady()`가 node ping 중
일시적인 `Invalid first byte`/`Connection reset by peer`로 2건을 실패시켰다. 실패 stack은
제품 코드가 아닌 Testcontainers launcher 초기화 경계였고, 같은 SHA에서 즉시 단독 재실행한
Cluster 17/17와 최종 순차 게이트 352/352 + 17/17가 모두 통과했다. 이 환경성 flake는
hosted Nightly에서 재확인할 PENDING 위험으로 유지한다.

## 운영·전달 경계

- registry/elector는 connection, client, Testcontainers fixture를 소유하거나 닫지 않는다.
- v3 write가 관찰된 뒤에는 old binary rollback이나 v3→v2 변환을 시도하지 않는다. writers를 멈추고 v2/colon/v3 상태와 diagnostics를 보존한 뒤 forward fix를 배포한다.
- `clusterTest`는 local/수동 Nightly 전용이다. 일반 `test`와 benchmark로 대체할 수 없다.
- 이슈 #854가 이미 존재하므로 중복 milestone issue는 만들지 않았다.
- PR, push, merge, tag/release, hosted Nightly dispatch는 이 리뷰의 로컬 DoD를 넘어서는 별도 승인 게이트다.

## 최종 판정

로컬 구현·테스트·정적 분석·ABI·문서 검토에서 P0/P1은 0이다. 그러나 hosted Nightly/PR/remote CI가 실행되지 않았으므로 이 결과는 **DONE (local implementation)** 이 아니라 **PENDING (delivery gate)** 로 인계한다.
