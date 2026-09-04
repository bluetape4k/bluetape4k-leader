# Redis Cluster strategic candidate 지원 lesson

Issue #854의 `leader-redis-lettuce` 구현에서 재사용할 규칙을 정리한다. 기준은 `origin/develop` `c47324bcff5738e5505cf733d838afd730bad226`이며 Lettuce는 immutable catalog ref `850959d0ea5f76ac7e2c442400f47653d5f95eed`가 resolve하는 `7.6.0.RELEASE`다.

## Key와 slot

- 새 v3 index/candidate/tombstone/migration-token key는 모두 `{lengthDelimited(lockName)}`를 공통 hash tag로 사용한다. node ID는 tag 밖에 둔다.
- `lockName`과 `nodeId`를 raw `:` delimiter로 이어 붙이지 않는다. UTF-8 byte length prefix가 경계와 다국어 값을 보존한다.
- `{`와 `}`가 들어간 lock name은 기존 validation에서 거부한다. 사용자가 만든 hash-tag 경계를 다시 해석하지 않도록 하는 안전장치다.
- `DEFAULT`와 `GROUP` prefix는 서로 다른 namespace를 유지한다. 같은 lock name이라도 후보가 섞이지 않아야 한다.

근거: `LettuceCandidateKeyCodec.kt:23-58`, `LettuceCandidateKeyCodecTest`, `LettuceCandidateKeyIsolationTest`.

## Legacy migration

- v2와 colon legacy source는 호환 읽기·이행 전용이다. source `GET`/`PTTL`은 각각 단일 key로 수행하며 source key를 multi-key Lua `KEYS`에 넣지 않는다.
- v3 destination은 `SET NX`와 same-slot `SADD`/`PERSIST`로 기록한다. 이미 destination이 있으면 값을 덮어쓰지 않고 index만 repair한다.
- source는 migration 중 삭제하지 않는다. unregister의 명시적 cleanup에서만 nodeId payload를 확인한 뒤 candidate를 삭제하고 index member를 제거한다.
- `GET`과 `PTTL` 사이의 만료는 cross-slot 원자화가 불가능하므로 bounded race로 기록한다. post-copy 재확인에서 source가 사라진 경우에만 destination을 best-effort 정리한다.
- `PTTL=-1`은 persistent source이며 만료가 아니다. `-2` 또는 관찰 후 실제 0ms 만료만 expired로 취급한다.
- public TTL 입력은 Redis 쓰기 전에 검증한다. 음수와 1ms 미만 양수는 거부하고 `Duration.ZERO`는 persistent 후보로 허용한다. Lua도 정수·비음수 TTL만 받아 직접 호출의 우회를 막는다.
- destination payload가 malformed이면 legacy source로 조용히 대체하지 않고 기존 codec 예외를 표면화한다. 유효한 다른 node ID가 있으면 해당 v3 destination/index를 정리한 뒤 migration을 재시도한다.

근거: `LettuceCandidateRegistry.kt:244-314`, `LettuceSuspendCandidateRegistry.kt:249-319`, `LettuceCandidateWriteScriptTest`의 persistent-source/TTL 테스트, 실제 Cluster migration 테스트.

## Lifecycle fence와 ownership

- `UNREGISTER`는 candidate/token 삭제와 index `SREM`보다 먼저 persistent tombstone을 세운다. 그 뒤 실행되는 migration은 tombstone을 보고 아무것도 되살리지 않는다.
- 새 `REGISTER`만 tombstone과 stale migration token을 지우고 새 generation을 시작한다.
- migration이 성공한 호출만 unique token을 보유한다. cleanup은 destination raw value와 token이 모두 일치할 때에만 candidate/index/token을 삭제한다.
- regular `refresh`, `updateResult`, `register`가 token을 지워 늦은 migration cleanup이 현재 writer를 삭제하지 못하게 한다.

근거: `LettuceCandidateWriteScript.kt:31-101`, `LettuceCandidateRefreshScript.kt:77-80`, `LettuceCandidateResultScript.kt:184-188`, write-script barrier/token 테스트.

## Capability와 coroutine parity

- direct command, script command, batch value reader를 하나의 concrete API cast로 묶지 않는다. standalone은 `RedisCommands`/`RedisCoroutinesCommands` native `MGET`, Cluster는 `RedisAdvancedClusterCommands`/coroutine common `MGET`을 사용한다.
- `RedisScriptRunner` overload는 기존 standalone descriptor를 삭제하지 않고 Cluster/common scripting capability를 추가한다. `NOSCRIPT`는 원문 fallback, 다른 예외와 `CancellationException`은 원인/취소 semantics를 보존한다.
- connection, client, container는 caller/fixture가 소유한다. registry/elector가 닫거나 shutdown하지 않는다.

근거: `LettuceCandidateCommands.kt:15-92`, `LettuceSuspendCandidateCommands.kt:15-90`, `script/RedisScript.kt:47-194`, suspend Cluster tests.

## Fixture·CI gate

- actual Cluster 검증은 기존 `RedisClusterServer.Launcher.redisCluster`를 사용한다. raw container를 새로 만들지 않는다.
- `clusterTest`는 regular `test`에서 `redis-cluster` tag를 제외하고, test classes/classpath, `failOnNoDiscoveredTests`, checked-in 17개 manifest exact-name, XML `tests>=manifest`, `skipped=0`, 직렬 실행을 강제한다. 테스트 범위가 축소되거나 전부 skip된 경우에도 성공하지 않도록 Gradle과 CI 양쪽에서 이름·count를 검사한다.
- CI success/failure 시 image reference와 실제 `@sha256:` digest, `cluster_state`, 6개 endpoint, mapped ports, Docker inspect/logs/events를 남긴다. local 성공은 hosted Nightly 성공으로 간주하지 않는다.
- fixture의 `awaitClusterReady()`가 첫 시작에서 일시적인 node ping `Invalid first byte`/`Connection reset by peer`를 낼 수 있다. 이를 제품 테스트 통과로 오인하지 말고 동일 SHA에서 즉시 재실행하며, 재현되면 launcher/image/daemon diagnostics와 함께 hosted 환경성 flake로 분리한다.
- `MOVED`/`ASK` topology 수렴과 failover performance는 이슈 범위 밖이다. 이 경계를 넘으려면 별도 benchmark/운영 이슈가 필요하다.

근거: `leader-redis-lettuce/build.gradle.kts:35-76`, `.github/workflows/nightly-tests.yml:203-269,1294-1345`, `LettuceStrategicRedisClusterTest.kt:1-576` (17개 lifecycle/migration/cancellation 테스트와 runtime provenance helper).

## ABI·문서·전달

- Kotlin default parameter를 public constructor에서 제거하지 않는다. standalone `@JvmOverloads`와 compiler synthetic bridge를 기준 artifact의 `javap -p -s`와 precompiled Kotlin consumer로 확인한다.
- Cluster constructor는 additive public descriptor로 추가한다. Java fixture가 explicit two-arg call을 compile해야 한다.
- README 양국어에는 지원 버전, key/migration/rollback 경계, caller-owned connection, `clusterTest`/Nightly 위치를 같이 적는다.
- v3 write 후 old binary rollback이나 v3→v2 parity 변환은 지원하지 않는다. writers 정지 → 상태·diagnostics 보존 → forward fix 순서로 대응한다.
- 기존 Issue #854가 milestone `1.1.0`에 있으므로 중복 issue를 만들지 않는다. PR/push/merge/Nightly dispatch는 fresh exact-head 승인 게이트다.

## 이번 실행의 검증 표본

- regular module test: 352 tests, failures/skipped/errors 모두 0.
- `clusterTest`: manifest의 17 tests, failures/skipped/errors 모두 0; exact names와 runtime provenance guard 통과.
- `koverXmlReport`, root `detekt`, Gradle/custom ABI, `dependencyInsight`, static validators, `actionlint`, YAML parser, old Kotlin/new Java consumer compile 통과.
- hosted Nightly와 PR은 실행하지 않았으므로 최종 전달 상태는 `PENDING`이다. `tommy351/redis-cluster:6.2`는 mutable tag이므로 digest pinning과 failover stress는 후속 hosted/fixture 이슈로 남긴다.
