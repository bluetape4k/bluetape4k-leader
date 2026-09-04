# Issue #854 Redis Cluster 공식 지원 설계

## 문서 상태

- 상태: 독립 검토 결과와 안전 보정을 반영한 승인 완료 설계 — 로컬 구현·검증 완료, hosted delivery gate 대기
- 작성일: 2026-09-04
- 대상 저장소: `bluetape4k/bluetape4k-leader`
- 대상 모듈: `leader-redis-lettuce`
- 이슈: [#854](https://github.com/bluetape4k/bluetape4k-leader/issues/854)
- 기준 커밋: `c47324bcff5738e5505cf733d838afd730bad226` (`origin/develop`)
- 작업 branch: `feat/issue-854-redis-cluster`
- 승인 범위: Redis Cluster를 사용하는 strategic candidate registry의 공식 지원
- 제외 범위: Cluster failover 성능 benchmark, 일반 lock/group primitive의 추가 재설계, publish/release/tag, PR·merge

## SPW-01 — 독자·목적·근거 고정

### 독자

`leader-redis-lettuce`의 API 사용자, Redis 운영자, 구현자와 리뷰어를 대상으로 한다. 사용자는 standalone Redis 연결을 이미 알고 있다고 가정하되, Redis Cluster의 same-slot 제약과 Lettuce 연결 타입은 설명한다.

### 목적

다음 질문에 구현 전에 답한다.

1. 어떤 public API로 `StatefulRedisClusterConnection`을 받는가?
2. candidate index와 candidate value가 왜 항상 한 hash slot에 놓이는가?
3. 기존 v2·colon legacy 자료를 어떻게 잃지 않고 v3으로 읽고 이행하는가?
4. blocking/suspend 경로와 Lua cleanup이 실제 Cluster에서 `CROSSSLOT` 없이 동작하는가?
5. 문서·ABI·테스트가 공식 지원 주장을 어떻게 입증하는가?

### 근거 계층

| 근거 | 역할 |
|---|---|
| [Redis Cluster specification](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/) | CRC16 hash slot, `{...}` hash tag, multi-key same-slot 계약 |
| [Redis multi-key operations](https://redis.io/docs/latest/develop/using-commands/multi-key-operations/) | `MGET`·transaction·Lua의 same-slot 및 `CROSSSLOT` 동작 |
| [Redis scripting API](https://redis.io/docs/latest/develop/programmability/lua-api/) | Lua에 전달하는 declared keys의 Cluster 제약 |
| [Lettuce architecture](https://github.com/redis/lettuce/blob/main/.agents/docs/architecture.md) | `RedisClusterClient`와 `StatefulRedisClusterConnection` 연결 모델 |
| local source | 현재 v2/legacy codec, registry script, strategic elector와 테스트 계약 |
| sibling `infra/lettuce` | 공통 `RedisScriptingCommands`/`RedisScriptingAsyncCommands`와 Cluster overload 패턴 |

Redis 공식 문서의 핵심 제약은 하나의 command, transaction 또는 Lua script에 전달되는 여러 key가 같은 hash slot에 있어야 한다는 것이다. hash tag의 `{...}` 안쪽 substring만 slot 계산에 사용되며, 기본 slot 수는 16384이다. 이는 설계의 외부 계약이고 로컬 테스트보다 우선한다.

## SPW-02 — 산출물 계약

이 문서는 다음 항목을 반드시 포함한다.

- 선택한 접근과 거부한 대안
- public API 및 ABI 호환성
- v3 key layout과 slot 불변식
- v2·colon legacy 읽기/이행·TTL·우선순위
- blocking/suspend 명령 및 취소 경계
- 오류·동시성·mixed-version 동작
- 테스트와 정적/ABI/문서 검증 매트릭스
- rollout, rollback, non-goals 및 acceptance criteria

## 1. 현재 구현과 문제

현재 `LettuceCandidateKeyCodec`은 다음 v2/legacy 키를 만든다.

```text
v2 index:     <prefix>|v2|i|<lengthDelimited(lockName)>
v2 candidate: <prefix>|v2|c|<lengthDelimited(lockName)><lengthDelimited(nodeId)>
legacy index: <prefix>:<lockName>
legacy value: <prefix>:<lockName>:<nodeId>
```

`LettuceCandidateRegistry`의 `refreshCandidate`와 stale-index cleanup은 index와 candidate를 함께 Lua에 전달하고, `listCandidates`는 candidate 여러 개를 `MGET`한다. v2/legacy 키에는 hash tag가 없으므로 Redis Cluster에서 이 다중 키 호출은 서로 다른 slot으로 라우팅될 수 있다. 따라서 단순히 `StatefulRedisClusterConnection` overload만 추가하면 공식 지원이 아니다.

현재 호출 경로는 다음과 같다.

| 계층 | blocking | suspend |
|---|---|---|
| public strategic elector | `LettuceStrategicLeaderElector`, `LettuceStrategicLeaderGroupElector` | `LettuceStrategicSuspendLeaderElector`, `LettuceStrategicSuspendLeaderGroupElector` |
| registry | `LettuceCandidateRegistry` | `LettuceSuspendCandidateRegistry` |
| script | `RedisScriptRunner.run` | `runSuspending` |
| 기존 검증 | `LettuceCandidateKeyIsolationTest`, `LettuceStrategicHeartbeatTest` | 동일 테스트의 suspend 경로 |

일반 `LettuceLock`, `LettuceSlotTokenGroup` 등은 이번 이슈의 대상이 아니다. 특히 `LettuceSlotTokenGroup`은 이미 `lg:{lockName}` 형식의 same-slot key 계약을 갖고 있으므로 중복 재설계하지 않는다.

## 2. 대안과 선택

### 대안 A — Cluster 전용 registry/elector 추가

Cluster용 클래스를 별도로 만들고 기존 standalone 구현은 유지한다.

- 장점: 기존 코드의 변경 범위가 작다.
- 단점: blocking/suspend와 migration 로직이 이중화되고, bug fix와 테스트 parity가 쉽게 어긋난다.
- 판정: 거부. public API는 늘지만 공통 계약을 보장하지 못한다.

### 대안 B — 모든 Redis primitive를 Cluster-aware로 재설계

일반 lock, semaphore, group primitive까지 하나의 공통 추상화로 바꾼다.

- 장점: 장기적으로 공통화된 facade를 만들 수 있다.
- 단점: Issue #854 범위를 크게 넘고, 이미 Cluster key 계약을 가진 primitive까지 ABI·운영 위험을 만든다.
- 판정: 거부. 별도 이슈와 migration plan이 필요한 broad refactor다.

### 대안 C — 공통 command adapter + v3 hash-tag key + 기존 constructor 보존 (선택)

Lettuce가 제공하는 common sync/async Cluster command interface로 registry를 일반화하고, 기존 standalone constructor는 유지한다. 새 v3 key에서 lock name을 hash tag로 묶고 v2/legacy를 읽어 v3으로 이행한다. 단일 키·set·script 명령은 공통 interface를 사용하고, v3 후보 일괄 조회는 private reader capability로 분리한다. standalone은 `RedisCommands.mget`, Cluster는 `RedisAdvancedClusterCommands.mget`을 사용해 기존 결과 계약을 유지한다.

- 장점: 기존 ABI를 보존하고, 실제 Cluster와 standalone이 같은 registry·script·테스트 경로를 공유한다.
- 단점: v3 rollout 동안 이전 버전과의 동시 쓰기와 cutover 이후 rollback은 지원하지 않으며, 별도 운영 절차가 필요하다.
- 판정: 선택. 변경 범위가 strategic registry로 한정되고 same-slot 불변식을 코드·테스트·문서에서 직접 입증한다.

## 3. 설계

### 3.1 Public API와 ABI

네 개 public class에 `StatefulRedisClusterConnection<String, String>` 생성자를 추가한다.

```kotlin
class LettuceStrategicLeaderElector private constructor(
    private val registry: LettuceCandidateRegistry,
    override val nodeId: String,
) : StrategicLeaderElector {
    @JvmOverloads
    constructor(
        connection: StatefulRedisConnection<String, String>,
        nodeId: String = Uuid.V7.nextBase62(),
    ) : this(LettuceCandidateRegistry(connection), nodeId)

    @JvmOverloads
    constructor(
        connection: StatefulRedisClusterConnection<String, String>,
        nodeId: String = Uuid.V7.nextBase62(),
    ) : this(LettuceCandidateRegistry(connection), nodeId)
}
```

동일한 형태를 다음 클래스에 적용한다.

- `LettuceStrategicLeaderGroupElector`
- `LettuceStrategicSuspendLeaderElector`
- `LettuceStrategicSuspendLeaderGroupElector`

기존 `StatefulRedisConnection` 생성자의 one-/two-argument JVM descriptor와 Kotlin default constructor synthetic bridge는 유지한다. 구현 전 develop 산출물에서 `javap -p -s`로 현재 descriptor set을 저장하고, 구현 후 동일 명령과 precompiled Kotlin one-argument consumer fixture로 `(StatefulRedisConnection, String)`, `(StatefulRedisConnection)`, 그리고 기존 synthetic `(StatefulRedisConnection, String, int, DefaultConstructorMarker)` 호출이 모두 유지되는지 확인한다. `@JvmOverloads`는 standalone secondary constructor에 적용해 Java/Kotlin 호출 surface를 명시적으로 보존하고, Cluster overload는 새 descriptor로 추가한다. caller-owned connection은 닫거나 shutdown하지 않는다. core interface와 nodeId·result·contention 계약은 변경하지 않는다.

registry의 internal command adapter는 다음 capability를 분리한다.

- blocking 단일 키·set·script 명령: `io.lettuce.core.cluster.api.sync.RedisClusterCommands<String, String>`.
- blocking v3 일괄 조회: `RedisAdvancedClusterCommands.mget`를 노출하는 private `CandidateValueReader` adapter. Cluster connection은 이 capability를 사용하고, standalone `RedisCommands.mget` connection은 같은 reader 계약을 사용한다. 따라서 `mget`을 mutation 공통 타입에 억지로 선언하지 않는다.
- suspend direct 명령: `io.lettuce.core.cluster.api.coroutines.RedisClusterCoroutinesCommands<String, String>`. `RedisCoroutinesCommands`는 이 Cluster 공통 interface의 subtype이고, Cluster connection은 `StatefulRedisClusterConnection.coroutines()` extension을 사용한다. `mget`을 포함한 coroutine 명령과 nullable reply semantics를 그대로 보존한다.
- suspend script 명령: `io.lettuce.core.api.async.RedisScriptingAsyncCommands<String, String>`. standalone의 `RedisAsyncCommands`와 Cluster의 `RedisAdvancedClusterAsyncCommands` 모두 이 script capability를 제공하며, `await()` cancellation 경계를 유지한다.

이 타입 분리는 현재 dependency catalog가 실제로 resolve하는 Lettuce `7.6.0.RELEASE` API hierarchy를 기준으로 한다. 저장소의 immutable catalog ref `850959d0ea5f76ac7e2c442400f47653d5f95eed`와 sibling `bluetape4k-dependencies/gradle/libs.versions.toml`의 `lettuce = "7.6.0.RELEASE"`를 기준 증거로 삼으며, 이 이슈에서는 7.7 upgrade나 전역 catalog 변경을 수행하지 않는다. Cluster 공통 `RedisClusterCommands`/`RedisClusterAsyncCommands`에는 `mget`이 없고 Cluster 전용 `RedisAdvancedClusterCommands`/`RedisAdvancedClusterAsyncCommands`에만 있으므로, Cluster value reader는 Advanced capability를 사용한다. standalone `RedisCommands`/`RedisCoroutinesCommands`는 자체 `mget`을 사용한다. 구현 전에 해당 public API와 `StatefulRedisClusterConnection.sync()/async()/coroutines()`의 실제 컴파일을 acceptance에 포함한다. 실제 resolved version은 `dependencyInsight`로 다시 기록한다.

### 3.2 Script runner 공통화

`RedisScriptRunner`는 기존 overload를 삭제하지 않고 다음 overload를 추가한다.

```kotlin
fun <T> run(
    commands: RedisScriptingCommands<String, String>,
    script: RedisScript,
    outputType: ScriptOutputType,
    keys: Array<String>,
    vararg args: String,
): T

fun <T> runAsync(
    commands: RedisScriptingAsyncCommands<String, String>,
    script: RedisScript,
    outputType: ScriptOutputType,
    keys: Array<String>,
    vararg args: String,
): CompletableFuture<T>

suspend fun <T> runSuspending(
    commands: RedisScriptingAsyncCommands<String, String>,
    script: RedisScript,
    outputType: ScriptOutputType,
    keys: Array<String>,
    vararg args: String,
): T
```

기존 standalone overload는 호환성을 위해 유지하고 공통 private 실행 함수로 위임한다. `NOSCRIPT` fallback과 예외 원인, suspend cancellation semantics를 바꾸지 않는다.

### 3.3 v3 key layout과 slot 불변식

현재 key 생성 함수는 v3을 반환하도록 바꾸고, v2 생성 함수는 migration/test용으로 보존한다.

```text
v3 index:     <prefix>|v3|i|{<lengthDelimited(lockName)>}
v3 candidate: <prefix>|v3|c|{<lengthDelimited(lockName)>}<lengthDelimited(nodeId)>
v3 tombstone: <prefix>|v3|t|{<lengthDelimited(lockName)>}<lengthDelimited(nodeId)>
v3 mig token: <prefix>|v3|m|{<lengthDelimited(lockName)>}<lengthDelimited(nodeId)>
v2 index:     <prefix>|v2|i|<lengthDelimited(lockName)>
v2 candidate: <prefix>|v2|c|<lengthDelimited(lockName)><lengthDelimited(nodeId)>
colon legacy: <prefix>:<lockName>[:<nodeId>]
```

`lengthDelimited`는 기존처럼 lock name의 UTF-8 byte length와 원문을 함께 보존한다. v3 index, candidate, lifecycle tombstone/fence, migration token은 동일한 `{<lengthDelimited(lockName)>}` substring을 가지므로 `StringCodec.UTF8` wire key 기준 `SlotHash.getSlot` 결과가 같다. `keyPrefix`는 `DEFAULT_KEY_PREFIX`와 `GROUP_KEY_PREFIX`로 달라지므로 namespace key는 서로 다른 Redis key이고, 같은 lock name에 대해 같은 slot을 사용한다.

`validateLockName`의 `{`/`}` 거부를 유지한다. 이 검증은 사용자가 hash tag를 닫거나 추가해 다른 key를 같은 logical operation에 끼워 넣는 hashtag injection을 막는다. nodeId는 closing brace 뒤에 있으므로 nodeId의 brace는 slot 경계를 바꾸지 않는다.

필수 invariant:

1. 하나의 lock name에 대한 v3 index와 v3 candidate는 같은 slot이다.
2. `refresh` Lua의 `KEYS[1]`/`KEYS[2]`는 v3 candidate/index이고 `KEYS[3]`은 migration token이며, token이 있으면 같은 원자 경계에서 지운다. `updateResult`도 v3 candidate/token을 함께 받아 regular writer가 migration ownership을 해제한다.
3. stale cleanup Lua의 첫 index key와 모든 candidate key는 v3만 전달한다.
4. v3 `MGET`은 candidate reader가 제공하는 v3 key에만 사용한다. Cluster에서는 모든 key가 같은 lockName hash tag를 가지며, standalone은 `RedisCommands.mget`을 사용한다.
5. v2/legacy migration과 cleanup은 multi-key 명령을 사용하지 않는다.
6. lifecycle tombstone/fence와 migration token도 candidate/index와 같은 slot이며, source(v2/colon) key는 multi-key script에 전달하지 않는다.
7. 서로 다른 lock name의 candidate pool은 key가 분리되고, 서로 다른 namespace도 key가 분리된다.

standalone `RedisCommands`는 `RedisStringCommands`를 통해 `mget`을 제공하므로 기존 일괄 조회 계약을 유지한다. Cluster adapter는 `RedisAdvancedClusterCommands.mget`을 사용한다. 두 경로 모두 v3 candidate key만 전달하며, v2/colon source는 단건 `GET`/`PTTL` 경계를 유지한다.

### 3.4 읽기 우선순위와 migration

읽기 우선순위는 `v3 > v2 > colon legacy`다.

#### list

1. v3 index를 `SMEMBERS`로 읽고 v3 candidate key만 조회한다. standalone과 Cluster adapter 모두 실제 `MGET`을 사용하며, Cluster에서는 same-slot capability로 라우팅한다.
2. v2 index와 colon index는 각각 `SMEMBERS`로 읽는다.
3. v2 candidate와 legacy candidate는 각 key를 단건 `GET`한다. 이 경로에서는 v2/legacy key가 서로 다른 slot이어도 `MGET`하지 않는다.
4. candidate payload의 `nodeId`가 index member와 다르면 노출하지 않고 해당 index member를 제거한다.
5. source를 migration하기 전에 같은 node의 v3 lifecycle tombstone/fence를 확인한다. tombstone이 있으면 source candidate를 노출하거나 migration하지 않는다.
6. v2 또는 legacy candidate가 유효하면 TTL을 읽고 v3 migration script를 호출한다. script는 v3 candidate `SET NX`, migration token 기록, v3 index `SADD`/`PERSIST`를 한 same-slot 원자 경계로 수행하며, destination이 이미 있으면 값을 덮어쓰지 않고 index만 repair한다.
7. v3가 이미 있으면 v3 값을 우선하고 source를 덮어쓰지 않는다.
8. legacy/v2 source key는 migration 직후 삭제하지 않는다. source 보존은 진단과 forward-fix에 필요하며, `unregisterCandidate`가 exact node에 대해 세 버전을 정리한다.
9. v3 stale candidate cleanup script는 v3 index/candidate/token만 처리한다. v2/legacy index 정리는 단일 `SREM`으로 처리한다.

동일 node의 source가 여러 버전에 동시에 존재할 때는 다음 표를 적용한다.

| v3 | v2 | colon legacy | 선택 결과 | 정리/이행 |
|---|---|---|---|---|
| 유효 | 임의 | 임의 | v3 | source는 보존하고 index 누락만 repair |
| 없음/불일치 | 유효 | 임의 | v2 | v3로 `SET NX` migration, v2 source 보존 |
| 없음/불일치 | 없음/불일치 | 유효 | legacy | v3로 `SET NX` migration, legacy source 보존 |
| 없음/만료 | 없음/만료 | 없음/만료 | 없음 | index member만 version별 제거, candidate 부활 금지 |

payload decode 오류는 더 낮은 우선순위 source로 조용히 숨기지 않고 기존 malformed 예외 계약을 따른다. index member와 payload의 nodeId가 다르면 해당 version의 member만 제거한다.

#### refresh/updateResult

1. v3 candidate를 먼저 확인한다.
2. 없으면 v2, 그 다음 legacy exact node candidate를 단건 조회·이행한다.
3. destination이 이미 있으면 destination 값을 우선한다.
4. v3 candidate가 확보된 뒤 `refresh`는 v3 index/token과 함께 same-slot Lua를 실행하고, `updateResult`는 v3 candidate/token을 함께 받는 same-slot Lua를 실행한다. 두 regular writer 모두 migration token을 지워 이후 expiry cleanup이 자신이 만든 최신 값을 삭제하지 못하게 한다.
5. source가 만료되었거나 없으면 새 candidate를 만들지 않는다. `refresh`는 `ABSENT`를 반환하고 `updateResult`는 no-op 상태를 유지한다.

#### unregister

먼저 v3 same-slot `UNREGISTER` Lua가 persistent lifecycle tombstone/fence를 설정하고 v3 candidate·migration token을 `DEL`, v3 index member를 `SREM`한다. tombstone은 이후 source migration이 unregister를 되살리지 못하게 하는 분산 lifecycle 경계다. 그 다음 v2 candidate, colon candidate와 각 legacy index member를 서로 독립적인 single-key `DEL`/`SREM`으로 정리한다. source key는 이 cleanup 단계에서만 삭제하며 migration Lua의 `KEYS`에는 절대 넣지 않는다. 여러 slot의 key를 한 command 또는 Lua call에 묶지 않으므로 Cluster에서 `CROSSSLOT`을 발생시키지 않는다. legacy candidate payload의 nodeId가 요청 nodeId와 다르면 삭제하지 않는다. 이후 새 `REGISTER`는 같은 slot의 script에서 tombstone과 stale token을 먼저 지운 뒤 candidate/index를 기록한다.

#### TTL

- `Duration.ZERO`: destination을 persistent `SET`/`SETNX`로 만든다.
- 양수 TTL: source `PTTL`을 읽고 `SET ... NX PX <remaining>`으로 복사한다.
- `PTTL == -1`: persistent source로 간주한다.
- `PTTL == -2` 또는 0 이하의 만료 경쟁: source를 없는 것으로 간주한다.

TTL은 candidate value에만 둔다. index set은 `PERSIST`하여 한 finite candidate가 같은 lock name의 persistent candidate를 가리지 않게 한다. `updateResult`는 Lua `KEEPTTL`로 기존 TTL을 보존하고, `refresh`는 요청 TTL로 교체한다.

`GET`과 `PTTL` 사이의 source 만료는 서로 다른 slot의 key를 원자적으로 검사할 수 없으므로 bounded race로 정의한다. 관찰한 `PTTL > 0`을 destination TTL의 상한으로 사용하고 migration script 직후 source를 재확인한다. 재확인에서 source가 만료되면 migration script가 발급한 unique token과 raw value를 함께 비교하는 same-slot `REMOVE_IF_VALUE`로 destination candidate/index/token을 best-effort 정리한다. 다른 writer가 같은 payload를 다시 썼거나 token을 지웠다면 삭제하지 않는다. source key 자체는 절대 이 script로 삭제하지 않으며, persistent zombie가 발생할 수 있는 bounded window와 그 진단 방법을 문서화한다. 이 경계와 `PTTL` 1ms/0/-1/-2 결과를 결정론적 테스트로 고정한다.

### 3.5 오류·동시성·취소

- Redis `WRONGTYPE` 처리와 malformed `CandidateInfo` 예외는 기존 계약을 유지한다.
- Cluster가 `MOVED`를 반환하는 경우 topology-aware Lettuce connection에 위임한다. 본 변경은 topology refresh policy나 failover 성능 보장을 추가하지 않는다.
- resharding 중 Redis가 `TRYAGAIN` 또는 연결 오류를 반환하면 호출자에게 전파한다. 자동 재시도 정책은 Lettuce client 설정의 책임이다.
- `refresh`와 stale cleanup의 atomicity는 기존 Lua 경계를 유지한다. key layout만 v3 same-slot으로 바꾼다.
- suspend direct command는 standalone/Cluster 모두 `RedisClusterCoroutinesCommands`를 사용하고, script만 `RedisScriptingAsyncCommands`의 `RedisFuture`를 `await()`한다. coroutine direct command와 script의 cancellation/error/null semantics를 각각 기존 경계와 대조하고, `CancellationException`을 일반 backend failure로 삼키지 않는다.
- caller-owned connection과 Testcontainers singleton lifecycle은 registry/elector가 소유하지 않는다.
- `REGISTER`는 v3 candidate, index, tombstone, migration token을 같은 slot의 Lua `KEYS`로 받아 tombstone과 이전 token을 먼저 지운 뒤 candidate write/index repair를 수행한다. negative TTL은 기존 caller validation/error contract를 유지하고, persistent는 `Duration.ZERO`에서만 허용한다.
- `MIGRATE`는 v3 candidate, index, tombstone, migration token 네 key만 받는다. tombstone이 존재하면 `ABSENT`를 반환하고 아무것도 만들지 않는다. source raw value/observed PTTL은 single-key command로 읽어 `ARGV`로만 전달하며, 성공적인 `SET NX` 뒤에는 이 호출을 식별하는 unique token을 token key에 persistent하게 기록한다. destination이 이미 있으면 token을 탈취하지 않고 index만 repair한다.
- `REMOVE_IF_VALUE`는 v3 candidate/index/token 세 key와 raw value/member/token 세 argument를 비교한다. candidate raw value와 token이 모두 일치할 때만 candidate를 삭제하고 index member와 token을 정리한다. source(v2/colon) key는 이 script의 `KEYS`에 포함하지 않으며 source 삭제는 unregister의 별도 single-key cleanup에서만 수행한다. candidate/index/token 조작을 한 same-slot Lua 경계로 묶어 중간 crash와 동일 payload writer 삭제를 방지한다.
- unregister와 migration의 경쟁은 두 script 중 Redis가 먼저 실행한 하나의 원자 순서로 결정된다. unregister가 먼저면 persistent tombstone이 migration을 거부하고, migration이 먼저면 unregister가 tombstone을 세운 뒤 destination을 제거한다. source read 이후 unregister가 끼어드는 결정론적 barrier 테스트로 이 순서를 입증한다.

v3와 v2를 동시에 쓰는 이전 바이너리와의 실시간 양방향 동기화는 제공하지 않는다. 따라서 mixed-version writer 운용과 cutover 이후 rollback은 지원하지 않는 명시적 안전 경계로 둔다.

| 단계 | 허용 writer/reader | migration | rollback 판정 |
|---|---|---|---|
| 준비 | 기존 v2/legacy writer만 | 새 binary를 기동하지 않으므로 없음 | 허용 |
| cutover | 모든 strategic writer를 일시 정지한 뒤 새 binary로 교체 | 모든 writer 교체 확인 후에만 허용 | v3 write 전까지만 허용 |
| 안정 | 새 v3 writer/reader만 | 허용, v2/legacy source 보존 | 구버전 binary rollback 금지, forward fix만 허용 |
| 비상 복구 | 모든 writer 정지 | 중단 | v3 key가 한 번이라도 쓰였으면 old binary 재기동 금지; source를 진단용으로 보존하고 forward fix만 수행 |

구버전 writer가 v2를 갱신한 뒤 새 reader가 stale v3를 우선하는 시나리오는 지원 계약 밖이며, integration test는 이를 감지 가능한 진단 상태로 고정한다. cutover 이후 장애에는 이 이슈가 별도 recovery tool이나 v3→v2 parity 변환을 제공하지 않는다. 모든 writer를 정지하고 v2/colon source와 v3 상태를 보존·조사한 뒤 forward fix를 배포하며, destructive conversion은 별도 승인된 migration 이슈로 분리한다. 이 절차를 지키지 않으면 leader safety와 중복 실행 방지를 보장하지 않는다.

## 4. 테스트 설계

### 4.1 단위/계약 테스트

| 대상 | 검증 |
|---|---|
| codec | v3 index/candidate가 같은 slot인지 `SlotHash.getSlot(StringCodec.UTF8.encodeKey(key))`로 확인 |
| codec | 서로 다른 lock name은 보통 다른 slot일 수 있지만 각 lock 내부 key는 항상 동일 slot인지 확인 |
| codec | `DEFAULT`와 `GROUP` prefix key가 서로 다르고 같은 lock name에서 same-slot인지 확인 |
| validation | lock name에 `{` 또는 `}`가 있으면 기존 validation 예외가 유지되는지 확인 |
| migration | v2 persistent/finite TTL과 colon legacy가 v3으로 복사되고 TTL이 보존되는지 확인 |
| precedence | v3 값이 v2/legacy와 다를 때 v3이 노출되는지 확인 |
| cleanup | v3 stale index cleanup이 유효 candidate를 지우지 않고, missing candidate만 제거하는지 확인 |
| API | 네 strategic elector의 standalone constructor와 Cluster constructor가 모두 노출되는지 ABI/API 검사로 확인 |
| migration race | 동시 reader가 하나의 destination만 만들고 source를 삭제하지 않는지, destination 기존 값과 index 누락을 repair하는지 확인 |
| lifecycle fence | source read와 migration 사이 unregister barrier에서 persistent tombstone이 resurrection을 막고, 이후 list/refresh/update가 source를 숨기는지 확인 |
| migration ownership | migration token과 raw value가 모두 일치할 때만 destination cleanup이 일어나고, 같은 payload를 다시 쓴 writer의 값은 보존되는지 확인 |
| migration TTL | `PTTL` 1ms/0/-1/-2, `GET` 직후 만료, expired source 비부활 및 bounded stale window를 확인 |
| script failure | `WRONGTYPE`, `NOSCRIPT` fallback, `MOVED`/`TRYAGAIN` 전파와 sync/async/suspend 반환·취소 semantics를 확인 |

### 4.2 실제 Redis Cluster 통합 테스트

기존 `RedisClusterServer.Launcher.redisCluster`와 `RedisClusterServer.Launcher.LettuceLib.getClusterClient`를 재사용한다. 테스트는 heavyweight fixture이므로 순차 실행하고, active Colima/Docker context를 먼저 확인한다.

`StatefulRedisClusterConnection` 하나에서 다음을 blocking과 suspend 각각 검증한다. 테스트 fixture는 `bluetape4k-testcontainers:2.1.0-SNAPSHOT`의 `RedisClusterServer`와 `tommy351/redis-cluster:6.2` image reference를 고정된 provenance로 기록하고, 실패 시 image reference/digest, Redis `cluster_state`, mapped endpoints, Docker inspect/logs/events를 출력한다. image digest 또는 fixture artifact가 바뀌면 acceptance 증거를 갱신한다.

1. `registerCandidate` 후 v3 index/value 존재와 `listCandidates` 결과
2. 여러 node의 `listCandidates`가 standalone과 Cluster 모두 v3 `MGET`으로 동작
3. `refreshCandidate` metadata/TTL 갱신 및 결과 counter 보존
4. `updateResult` success/failure counter와 TTL 보존
5. `unregisterCandidate`가 v3 index/value를 정리
6. v2와 colon legacy seed를 `list`/`refresh`/`update`에서 읽고 v3으로 이행
7. finite candidate 만료 후 stale index가 정리되고 zombie candidate가 생성되지 않음
8. v3 index와 candidate keys의 실제 wire slot이 동일함
9. index cleanup/refresh Lua 호출이 `CROSSSLOT` 없이 완료됨
10. `DEFAULT`와 `GROUP` strategic elector가 동일 lock name에서 서로의 후보를 노출하지 않음
11. source read 직후 unregister와 migration을 barrier로 교차시켜 destination 재생성과 source 노출이 발생하지 않음
12. migration token을 보유한 호출보다 늦은 동일 payload writer가 destination을 갱신해도 이전 cleanup이 값을 삭제하지 않음

공개 elector별 최소 matrix는 다음과 같다.

| elector | namespace | 필수 lifecycle |
|---|---|---|
| `LettuceStrategicLeaderElector` | `DEFAULT` | register/list/refresh/updateResult/unregister/migration/TTL |
| `LettuceStrategicLeaderGroupElector` | `GROUP` | register/list/refresh/updateResult/unregister/migration/TTL |
| `LettuceStrategicSuspendLeaderElector` | `DEFAULT` | register/list/refresh/updateResult/unregister/migration/TTL/cancellation |
| `LettuceStrategicSuspendLeaderGroupElector` | `GROUP` | register/list/refresh/updateResult/unregister/migration/TTL/cancellation |

각 matrix 행은 별도 test method 또는 parameterized case로 추적하고, 네 elector 모두에 대해 실제 Cluster `MGET`, refresh Lua, stale-cleanup Lua가 `CROSSSLOT` 없이 완료되는지 단정한다. v3 stale value와 변경된 v2 source를 함께 심는 case는 mixed-version drift가 지원 범위 밖임을 명시적으로 진단한다. 필수 method 이름은 `leader-redis-lettuce/src/test/resources/redis-cluster-test-matrix.txt`에 한 줄씩 고정하며, 현재 17개 항목을 Gradle과 Nightly XML guard가 모두 확인한다. suspend matrix에는 group cancellation과 직접 script capability의 취소 전파·caller connection 보존도 포함한다.

테스트가 단순 compile이나 mock 성공만으로 통과하지 않도록 실제 Cluster 응답, `SlotHash`, TTL 범위, persisted index, candidate payload를 함께 단정한다. `CROSSSLOT` 예외가 한 번이라도 발생하면 실패로 처리하고, Docker/Colima 또는 fixture를 사용할 수 없으면 skip을 PASS로 세지 않고 `PENDING`/`BLOCKED`로 보고한다. failover performance benchmark는 추가하지 않는다.

### 4.3 기존 회귀와 정적 검증

- 기존 standalone `leader-redis-lettuce` 테스트 전체를 fresh `--rerun-tasks`로 실행한다.
- 변경된 테스트를 먼저 targeted 실행한 뒤 모듈 전체 테스트를 실행한다.
- `@Tag("redis-cluster")`가 붙은 테스트는 `:bluetape4k-leader-redis-lettuce:clusterTest` 전용 task로 순차 실행하고, 일반 `test`에는 자동으로 섞지 않는다. Nightly/수동 verification lane에서 같은 task를 실행하며 fixture diagnostics를 보존한다.
- `detekt`와 binary API/ABI 검사를 실행한다. 정확한 명령은 `./gradlew checkBinaryCompatibility --no-daemon --no-configuration-cache --no-build-cache`와 repository static contract의 `check_binary_api.py`를 사용하고, 기존 standalone descriptor 및 네 Cluster overload의 `javap`/Kotlin·Java consumer compile 결과를 함께 기록한다.
- `git diff --check`와 README 양쪽 언어 source-to-doc 대조를 실행한다.
- Kover report는 결과를 관찰하되, report 누락을 성공으로 해석하지 않는다.

실행 순서와 판정 기준은 다음과 같다. (1) codec/migration/slot/script 단위 테스트가 0 failure/error여야 한다. (2) Cluster task가 실제 6-node fixture에서 0 `CROSSSLOT`/failure/error여야 한다. (3) 모듈 전체 test와 `koverXmlReport`, `detekt`, ABI/API, docs check가 각각 성공해야 한다. required task가 실행되지 않거나 fixture가 unavailable이면 해당 항목은 `N/A` 또는 `BLOCKED`로 남기고 전체 DoD를 `PENDING`으로 유지한다.

## 5. 문서와 사용 예

`leader-redis-lettuce/README.md`와 `README.ko.md`에 다음을 같은 의미로 추가한다.

- standalone과 Redis OSS Cluster 모두 지원한다는 범위
- Cluster 연결은 `RedisClusterClient.connect()`가 반환한 `StatefulRedisClusterConnection`을 strategic elector 생성자에 전달한다는 예
- 하나의 lock name에 속한 index/candidate key가 hash tag로 same-slot이 된다는 설명
- Lettuce가 `MOVED`/topology refresh를 담당하며 이 라이브러리가 failover latency/performance를 보장하지 않는다는 경계
- v2/colon legacy는 읽기·TTL 보존 migration 대상이며, mixed-version writer 동시 운용은 지원하지 않으므로 rolling deployment 순서를 지킨다는 주의
- failover benchmark가 공식 지원 범위가 아니라는 설명

지원 범위는 현재 catalog의 Lettuce `7.6.0.RELEASE` API hierarchy에서 검증하며, 이 이슈에서는 Lettuce 6.x 예시를 새 Cluster API의 최소 지원 버전으로 승격하지 않는다. 구현 시 `libs.lettuce.core`의 실제 resolved version을 `dependencyInsight`로 기록하고, `RedisClusterCommands`/`RedisAdvancedClusterCommands`/coroutine extension이 모두 컴파일되는 범위를 문서와 ABI check에 고정한다. Redis OSS Cluster topology refresh와 `MOVED` 처리는 Lettuce 설정에 위임하고, managed Redis vendor별 failover 차이는 지원 보장에 포함하지 않는다.

문서 parity check는 다음 항목을 양쪽 README에서 같은 순서와 의미로 확인한다: (1) 네 elector의 standalone/Cluster constructor 예제, (2) caller-owned connection close 책임, (3) v3 same-slot 및 v2/legacy migration, (4) mixed-version cutover/rollback 금지, (5) Lettuce resolved version과 failover 경계. README는 central manual을 복제하지 않고 모듈 진입점 역할만 한다.

## 6. 위험과 대응

| 위험 | 신호 | 대응/rollback |
|---|---|---|
| v2/legacy key를 다중 key command에 전달 | 실제 Cluster `CROSSSLOT` | migration 경로를 단건 command로 되돌리고 v3 key만 Lua/MGET에 허용 |
| hash tag injection | brace lock name에서 slot mismatch | `validateLockName` 유지, 거부 테스트 고정 |
| source TTL 복사 중 만료 | `PTTL == -2`, empty list | source를 absent로 처리하고 새 candidate를 만들지 않음 |
| v3/v2 precedence 오류 | 서로 다른 payload가 노출됨 | v3 > v2 > legacy 순서와 exact node 검증 강화 |
| suspend async 호출이 common interface와 맞지 않음 | compile 또는 cancellation test 실패 | direct는 `RedisClusterCoroutinesCommands`, script는 `RedisScriptingAsyncCommands` + `await()` 경로로 고정 |
| migration/register crash window | candidate/index 불일치 또는 unregister resurrection | v3 candidate/index SET·SADD·PERSIST와 tombstone/token fence를 same-slot Lua로 묶고 index repair·barrier race를 검증 |
| mixed-version writer drift | v2만 갱신되고 v3가 stale | cutover 전 writer quiesce, cutover 후 old binary rollback 금지, 상태 보존 후 forward fix |
| Cluster fixture 환경 실패 | Docker bind/포트/cluster_state 오류 | Colima/context/fixture 진단과 image/ref/digest를 보존 후 테스트를 `PENDING`/`BLOCKED`로 보고; skip을 PASS로 취급하지 않음 |
| ABI/API surface drift | descriptor·consumer compile 불일치 | release baseline dump, `checkBinaryCompatibility`, `javap`, Kotlin/Java consumer compile을 모두 통과시킴 |

rollback은 v3 write 전의 branch/commit 되돌리기만 허용한다. cutover 이후에는 모든 writer를 정지하고 이전 binary를 재기동하지 않는다. 이 이슈는 별도 복구 도구나 v3→v2 parity 변환을 제공하지 않으므로, v2/legacy source와 v3 상태를 보존해 진단한 뒤 forward fix를 배포한다. 검증되지 않은 destructive cleanup은 수행하지 않으며, 변환이 필요하면 별도 승인된 migration 이슈로 분리한다.

## 7. Acceptance criteria와 DoD

### Acceptance criteria

- [ ] four strategic electors에 Cluster connection overload가 있고 기존 standalone API/ABI가 유지된다.
- [ ] v3 index/candidate key가 lock name hash tag로 same-slot이 된다.
- [ ] v2와 colon legacy를 단건 명령으로 읽고 TTL을 보존하며 v3으로 이행한다. source는 migration 중 삭제하지 않는다.
- [ ] blocking/suspend register/list/refresh/updateResult/unregister가 실제 Redis Cluster에서 동작한다.
- [ ] standalone/Cluster adapter의 실제 `MGET`과 multi-key Lua가 v3 candidate key만 사용하고 `CROSSSLOT`이 재현되지 않는다.
- [ ] stale cleanup, malformed payload, exact node mismatch, migration concurrent reader, PTTL 1ms/0/-1/-2, source-expiry race 회귀가 테스트된다.
- [ ] unregister/migration lifecycle tombstone race와 migration token ownership race가 결정론적으로 테스트된다.
- [ ] 네 public elector 각각의 DEFAULT/GROUP lifecycle·TTL·migration·cancellation matrix가 실행된다.
- [ ] `@Tag("redis-cluster")` 전용 task, fixture provenance/diagnostics, resolved Lettuce version이 fresh evidence로 기록된다.
- [ ] README와 `README.ko.md`가 동일한 Cluster/mixed-version/failover 경계를 설명하고 parity check가 통과한다. resolved Lettuce는 `7.6.0.RELEASE`로 기록한다.
- [ ] `checkBinaryCompatibility`, static `check_binary_api.py`, `javap`, Kotlin/Java consumer compile에서 기존 standalone과 새 Cluster descriptor가 유지된다.
- [ ] module tests, `clusterTest`, detekt, ABI/API, diff-check가 fresh evidence로 수렴하고 P0/P1 finding이 없다. 실행 불가 항목은 PASS가 아니라 `N/A`/`BLOCKED`로 보고한다.

### DoD 보고 필드

최종 보고에는 다음을 포함한다.

- 변경 파일과 public API descriptor
- targeted/cluster/module/static/ABI/docs 검증 명령과 실제 결과
- 테스트 수(성공/실패/error/skip)와 Cluster fixture 상태
- 남은 gap: hosted CI, PR, merge, failover benchmark는 별도 gate로 `PENDING`
- `Required checks: X/Y; N/A: N; Blocked: N`
- unchecked checklist ID와 rollback/운영 주의사항
- spec review severity와 해소 근거: P0/P1/P2/P3 개수, 각 P1의 설계·테스트 대응, 미해소 항목

## 8. 독립 검토 결과와 해소

사용자 설계 승인 뒤 세 개의 read-only lane이 기준 커밋과 현재 Lettuce API/source를 대조했다. 모든 lane은 소스·문서 변경 없이 완료했다. 아래 REQUEST CHANGES 결과를 반영해 이 문서를 보정했으며, 버전·ABI·lifecycle fence·token·CI guard를 추가한 material revision에 대해 사용자가 fresh `승인`을 재확인했다. 구현·로컬 검증은 완료했고 hosted delivery gate는 별도로 남긴다.

| lane | 판정 | P0 | P1 | P2 | 핵심 대응 |
|---|---|---:|---:|---:|---|
| `spec-architecture` | REQUEST CHANGES | 0 | 2 | 4 | command capability, suspend cancellation, rollout/rollback, TTL/crash/source precedence를 본문과 acceptance에 추가 |
| `spec-test-risk` | REQUEST CHANGES | 0 | 0 | 6 | `clusterTest`, fixture diagnostics/provenance, 네 elector matrix, 실제 `CROSSSLOT`, ABI/API 명령을 추가 |
| `spec-compatibility` | REQUEST CHANGES | 0 | 2 | 3 | command capability 분리, mixed-version rollback 금지 경계, README parity와 resolved Lettuce version을 추가 |

초기 P1은 다음과 같이 revised 설계와 구현에 반영했다. 아래 로컬 증거로 해소를 확인하며, hosted Nightly/PR/merge는 별도 승인 게이트다.

1. `mget`을 mutation 공통 타입에 선언하지 않고, standalone `RedisCommands`와 Cluster `RedisAdvancedClusterCommands`를 private `CandidateValueReader`로 분리했다. suspend direct는 `RedisClusterCoroutinesCommands`, script는 `RedisScriptingAsyncCommands`로 분리했다.
2. v3 우선순위와 양방향 동기화 부재를 숨기지 않고 mixed-version writer와 cutover 이후 rollback을 지원 범위 밖으로 확정했다. 모든 writer quiesce → 새 binary 전환 → migration 허용 순서를 운영 계약으로 고정하고, v3 write 뒤에는 old binary 재기동을 금지한다. 별도 recovery tool/parity 변환은 범위에서 제거하고 상태 보존 후 forward fix만 허용한다.
3. 현재 catalog의 resolved Lettuce를 `7.6.0.RELEASE`로 고정하고, 7.7 upgrade를 이슈 범위에서 제거했다. 기존 standalone default constructor의 synthetic JVM descriptor는 `@JvmOverloads`, `javap`, precompiled Kotlin consumer로 별도 보존 검증한다.
4. unregister→migration resurrection은 persistent v3 tombstone/fence와 source 숨김 규칙으로 차단한다. migration 성공 소유권은 unique token으로 기록하고 raw value와 token을 모두 비교할 때만 destination cleanup을 허용한다. v2/colon source는 multi-key script에 전달하지 않는다.
5. clusterTest no-test/skip guard, full-scope Nightly aggregator, fixture diagnostics artifact, actual resolved-version 및 static validator evidence를 implementation plan의 executable gate로 고정한다.

P2는 implementation plan의 executable task와 acceptance test로 추적한다. source-expiry race는 cross-slot 원자성의 한계를 반영해 bounded stale window로 계약을 조정했고, migration/register는 v3 candidate/index same-slot Lua로 crash window를 줄인다. 실제 fixture·ABI/API·Kover 로컬 증거는 확보했으며 hosted CI/PR/merge는 `PENDING`이다.

## 9. SPW-03~05 문서 품질 기록

### SPW-03 — 한국어 기술 문체

- 독자에게 직접 필요한 계약과 제한을 먼저 적고, 영어는 API·명령·URL·식별자에만 남긴다.
- `CROSSSLOT`, `MOVED`, `TRYAGAIN`, `PTTL`, `MGET`, `Lua`, `ABI/API` 등 기계 토큰은 번역하지 않는다.
- 문장을 짧게 유지하고 “지원한다”와 “보장하지 않는다”를 구분한다.
- 구현자가 바로 파일·심볼·테스트를 찾을 수 있도록 local anchor와 acceptance를 함께 둔다.

### SPW-04 — 사실·출처·추적성

주요 주장마다 위의 Redis/Lettuce primary link 또는 local source anchor를 연결했다. 독립 review lane의 지적은 §8과 acceptance에 연결했으며, source-to-test-to-doc 추적은 implementation plan에서 파일별로 다시 펼친다. 실제 Cluster의 topology/failover 결과는 로컬 테스트 출력과 hosted CI를 분리해 보고한다.

### SPW-05 — 읽기 되돌림 기록

작성 후 다음을 재확인했다.

- 범위: strategic candidate registry와 docs/test로 제한
- 비범위: 일반 primitive, failover benchmark, release/PR/merge
- migration: v3 우선, v2 다음, colon legacy 마지막; source 보존
- same-slot: v3 keys만 `MGET`/Lua에 전달
- parity: blocking/suspend 네 API 모두 Cluster overload와 실제 통합 테스트 대상
- 승인: 초기 사용자 설계 승인을 바탕으로 작성했고 독립 review 결과를 반영했다. resolved version·lifecycle fence·ABI·CI guard를 추가한 revised 문서는 fresh 사용자 승인 후에만 plan·code mutation을 진행한다.
