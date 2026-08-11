# Issue #671 Contract Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** etcd, Consul, DynamoDB, Kubernetes Lease가 `leader-core`의 공통
leader-id/LockExtender 계약을 실제 backend 테스트로 채택하고, 지원/N/A
capability와 CI fan-out을 정적으로 고정한다.

**Architecture:** 각 backend의 `src/test/.../contract/`에 기존 abstract
fixture를 상속하는 concrete Kotlin 테스트를 추가한다. Virtual-thread
wrapper와 executor/slot/lease overload는 backend-local direct test로
검증한다. `scripts/ci/leader-contract-capabilities.json`과 validator가
테스트 파일, abstract base, N/A 사유, CI task 연결을 확인하며 기존
module test 및 K8s `k8sTest` fan-out은 그대로 사용한다.

**Tech Stack:** Kotlin, JUnit 5, `io.github.bluetape4k.assertions`,
Kotlin coroutines, Testcontainers/K3s, Gradle, Python 3.12 CI validator.

---

## 파일 구조와 책임

| 영역 | 파일 | 책임 |
|---|---|---|
| 계약 matrix | `scripts/ci/leader-contract-capabilities.json` | backend × contract × execution model의 supported/N/A 선언 |
| matrix 검증 | `scripts/ci/validate_leader_contract_matrix.py` | 파일/base/reason/CI task의 정적 검증과 self-test |
| CI 연결 | `.github/workflows/ci.yml` | 기존 `ci-contract` 단계에서 matrix validator 실행 |
| etcd 계약 | `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/` | etcd의 10개 공통 fixture subclass와 virtual direct test |
| Consul 계약 | `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/` | Consul의 10개 공통 fixture subclass와 executor direct test |
| DynamoDB 계약 | `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/` | DynamoDB의 10개 공통 fixture subclass와 두 virtual wrapper test |
| K8s 계약 | `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/` | K8s 수명주기 helper, 10개 공통 fixture subclass와 executor direct test |
| 설계 근거 | `docs/superpowers/specs/2026-08-12-issue-671-contract-fixtures-design.md` | 승인된 범위와 N/A 경계 |

각 backend의 공통 fixture 파일은 다음 10개 이름을 사용한다.

```text
<Backend>LeaderElectorLeaderIdContractTest.kt
<Backend>LeaderGroupElectorLeaderIdContractTest.kt
<Backend>AsyncLeaderElectorLeaderIdContractTest.kt
<Backend>AsyncLeaderGroupElectorLeaderIdContractTest.kt
<Backend>SuspendLeaderElectorLeaderIdContractTest.kt
<Backend>SuspendLeaderGroupElectorLeaderIdContractTest.kt
<Backend>LockExtenderContractTest.kt
<Backend>GroupLockExtenderContractTest.kt
<Backend>SuspendLockExtenderContractTest.kt
<Backend>SuspendGroupLockExtenderContractTest.kt
```

`<Backend>`은 `Etcd`, `Consul`, `DynamoDb`, `KubernetesLease`로 치환한다.
direct test는 `EtcdVirtualThreadLeaderElectorContractTest.kt`,
`ConsulExecutorOverloadContractTest.kt`,
`DynamoDbVirtualThreadContractTest.kt`,
`KubernetesLeaseExecutorOverloadContractTest.kt`로 별도 둔다.

## Task 1: capability matrix validator를 먼저 고정

**Files:**

- Create: `scripts/ci/leader-contract-capabilities.json`
- Create: `scripts/ci/validate_leader_contract_matrix.py`
- Create: `scripts/ci/validate_leader_contract_matrix_test.py`
- Modify: `.github/workflows/ci.yml:288-290`

- [ ] **Step 1: 지원 조합과 N/A 조합을 JSON으로 선언한다.**

각 backend에 대해 다음 10개 `supported` 행을 만든다. `test`는 이후
실제 파일의 repository-relative 경로를 사용하고 `base`는 정확한 abstract
fixture 클래스명을 사용한다.

```json
{
  "backend": "etcd",
  "module": "bluetape4k-leader-etcd",
  "contract": "leader-id-sync-single",
  "status": "supported",
  "test": "leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdLeaderElectorLeaderIdContractTest.kt",
  "base": "AbstractLeaderElectorLeaderIdContractTest"
}
```

반복 행의 계약/기본 클래스 매핑은 다음과 같다.

| contract | base |
|---|---|
| `leader-id-sync-single` | `AbstractLeaderElectorLeaderIdContractTest` |
| `leader-id-sync-group` | `AbstractLeaderGroupElectorLeaderIdContractTest` |
| `leader-id-async-single` | `AbstractAsyncLeaderElectorLeaderIdContractTest` |
| `leader-id-async-group` | `AbstractAsyncLeaderGroupElectorLeaderIdContractTest` |
| `leader-id-suspend-single` | `AbstractSuspendLeaderElectorLeaderIdContractTest` |
| `leader-id-suspend-group` | `AbstractSuspendLeaderGroupElectorLeaderIdContractTest` |
| `lock-extender-sync-single` | `AbstractSyncLockExtenderContractTest` |
| `lock-extender-sync-group` | `AbstractGroupLockExtenderContractTest` |
| `lock-extender-suspend-single` | `AbstractSuspendLockExtenderContractTest` |
| `lock-extender-suspend-group` | `AbstractSuspendGroupLockExtenderContractTest` |

direct 행은 `base` 없이 `direct: true`를 사용한다.

```json
{
  "backend": "etcd",
  "module": "bluetape4k-leader-etcd",
  "contract": "virtual-thread-single",
  "status": "supported",
  "test": "leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdVirtualThreadLeaderElectorContractTest.kt",
  "direct": true
}
```

모든 backend에 다음 N/A 행을 추가한다. `reason`은 비어 있지 않은
한국어 문장으로 유지한다.

```json
{
  "backend": "etcd",
  "module": "bluetape4k-leader-etcd",
  "contract": "lock-extender-async",
  "status": "na",
  "reason": "leader-core에 async LockExtender 공통 fixture가 없고 lexical lock context 계약을 정의하지 않는다."
}
```

전용 virtual-thread wrapper가 없는 Consul/K8s는 executor overload direct
행을 supported로 기록한다. etcd는 single wrapper direct 행과 group
executor overload direct 행을 기록한다. DynamoDB는 single/group wrapper
direct 행을 기록한다. 실제 source 확인 결과 public overload가 없으면
행을 `na`로 바꾸고 API 근거를 `reason`에 남긴다.

- [ ] **Step 2: validator의 static 규칙을 구현한다.**

`validate_leader_contract_matrix.py`는 표준 라이브러리만 사용하고 다음
CLI를 제공한다.

```text
python3 scripts/ci/validate_leader_contract_matrix.py --static
python3 scripts/ci/validate_leader_contract_matrix.py --self-test
```

구현 규칙:

1. JSON은 object의 `entries` 배열을 가지며 각 행에 `backend`, `module`,
   `contract`, `status`, `test`를 요구한다.
2. `status == "supported"`이면 `test`가 존재하는 일반 파일이어야 한다.
   `base`가 있으면 파일 내용에 정확한 base 토큰이 있어야 하고, `direct`
   행은 `direct == true`여야 한다.
3. `status == "na"`이면 `reason.strip()`이 비어 있지 않아야 하며,
   존재하지 않는 `test`를 허용하지 않는다.
4. 네 backend 각각에 leader-id sync single/group, suspend single/group와
   네 LockExtender sync/suspend 행이 하나씩 있어야 한다.
5. `module`은 `settings.gradle.kts`에서 `:bluetape4k-<suffix>`로 실제
   포함되어야 한다.
6. `ci.yml`에서 etcd/Consul/DynamoDB job이 각 module `:...:test`를,
   K8s job이 `:bluetape4k-leader-k8s:test`와 `:bluetape4k-leader-k8s:k8sTest`
   를 실행하는지 확인한다.
7. `--self-test`는 현재 matrix가 통과한 뒤 임시 복사본의 supported 파일
   경로를 깨뜨려 실패를 확인하고, N/A reason을 비워 실패를 확인한 뒤
   원본을 변경하지 않고 성공한다.

`validate_leader_contract_matrix_test.py`는 `unittest`로
`validate_entries(matrix, root, workflow)`의 성공 case와 다음 실패 case를
검증한다: missing supported file, missing abstract base token, empty N/A
reason. 테스트는 repository root를 temporary directory로 만들고 실제
workflow 문자열을 최소 fixture로 제공해 외부 Docker/Gradle에 의존하지
않는다.

- [ ] **Step 3: CI contract 단계에 validator 호출을 추가한다.**

`.github/workflows/ci.yml`의 기존 run block을 다음처럼 확장한다.

```yaml
          python3 scripts/ci/validate_ci_fanout.py --static
          python3 scripts/ci/validate_ci_fanout.py --self-test
          python3 scripts/ci/validate_leader_contract_matrix.py --static
          python3 scripts/ci/validate_leader_contract_matrix.py --self-test
```

- [ ] **Step 4: validator unit test, self-test와 기존 CI validator를 실행한다.**

Run:

```bash
python3 -m unittest scripts/ci/validate_leader_contract_matrix_test.py
python3 scripts/ci/validate_leader_contract_matrix.py --self-test
python3 scripts/ci/validate_ci_fanout.py --static
python3 scripts/ci/validate_ci_fanout.py --self-test
```

Expected: 세 명령 모두 exit code 0. matrix의 `supported` 경로는 아직
concrete Kotlin 파일이 생성되지 않았으므로 이 단계에서는 `--static`을
실행하지 않는다. 네 backend slice가 끝난 Task 6에서 matrix static을
실행한다.

- [ ] **Step 5: matrix/validator 변경을 독립 커밋한다.**

```bash
git add scripts/ci/leader-contract-capabilities.json \
  scripts/ci/validate_leader_contract_matrix.py .github/workflows/ci.yml
git commit -m "test: leader contract capability 검증을 CI에 고정"
```

커밋 메시지는 Lore trailer를 포함하고, matrix validator 자체-test와 기존
CI validator 결과를 `Tested:`에 기록한다.

## Task 2: etcd contract slice

**Files:**

- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdAsyncLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdAsyncLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdSuspendLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdSuspendLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdLockExtenderContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdGroupLockExtenderContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdSuspendLockExtenderContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdSuspendGroupLockExtenderContractTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdVirtualThreadLeaderElectorContractTest.kt`
- Modify: `scripts/ci/leader-contract-capabilities.json`

- [ ] **Step 1: blocking/group/async leader-id subclasses를 추가한다.**

각 class는 `@TestInstance(PER_CLASS)`를 사용하고 `AbstractEtcdLeaderTest`
의 singleton client를 재사용한다. 단일 blocking subclass의 완전한
패턴은 다음과 같다.

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderElectorLeaderIdContractTest : AbstractLeaderElectorLeaderIdContractTest() {
    companion object : KLogging() {
        val etcd = AbstractEtcdLeaderTest.etcd
    }

    override fun createElector(options: LeaderElectionOptions): LeaderElector =
        EtcdLeaderElector(
            AbstractEtcdLeaderTest.newClient(),
            EtcdLeaderElectionOptions(
                leaderOptions = options,
                keyPrefix = "contract-${Base58.randomString(8)}",
            ),
        )
}
```

각 test 파일은 `Base58.randomString(8)`으로 key prefix를 만든다. group/async
subclass는 각각 `EtcdLeaderGroupElector`, `LeaderGroupElector`,
`AsyncLeaderElector`, `AsyncLeaderGroupElector` 타입과
`EtcdLeaderGroupElectionOptions(leaderGroupOptions = options, keyPrefix = ...)`
를 사용한다. async subclass도 blocking elector를 반환해
`LeaderElector : AsyncLeaderElector` 상속을 직접 검증한다.

- [ ] **Step 2: suspend leader-id subclasses를 추가한다.**

단일 suspend subclass는 다음 exact shape를 사용한다.

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdSuspendLeaderElectorLeaderIdContractTest : AbstractSuspendLeaderElectorLeaderIdContractTest() {
    companion object : KLoggingChannel() {
        val etcd = AbstractEtcdLeaderTest.etcd
    }

    override fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        EtcdSuspendLeaderElector(
            AbstractEtcdLeaderTest.newClient(),
            EtcdLeaderElectionOptions(leaderOptions = options, keyPrefix = "contract-${Base58.randomString(8)}"),
        )
}
```

group suspend는 `EtcdSuspendLeaderGroupElector`와
`EtcdLeaderGroupElectionOptions`를 사용한다. `KLoggingChannel` 및
`SuspendLeaderElector` import는 기존 Mongo/Lettuce suspend contract
패턴과 동일하게 유지한다.

- [ ] **Step 3: 네 LockExtender subclass를 추가한다.**

단일 sync class는 아래처럼 `elector`를 property로 고정한다.

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLockExtenderContractTest : AbstractSyncLockExtenderContractTest() {
    companion object : KLogging() {
        val etcd = AbstractEtcdLeaderTest.etcd
    }

    override val elector: LeaderElector = EtcdLeaderElector(
        AbstractEtcdLeaderTest.newClient(),
        EtcdLeaderElectionOptions(keyPrefix = "contract-${Base58.randomString(8)}"),
    )
}
```

group/suspend/group-suspend는 각각 `maxLeaders = 2`인
`EtcdLeaderGroupElectionOptions` 또는 `EtcdLeaderGroupElectionOptions`를
주입하고 기존 네 abstract base를 상속한다. 새 테스트에서 `assertThrows`,
`kotlin.test.assertFailsWith`, `invoking { } shouldThrow`를 사용하지 않는다.

- [ ] **Step 4: etcd virtual wrapper와 slot 결과를 직접 검증한다.**

`EtcdVirtualThreadLeaderElector`를 실제로 감싸고, core default bridge가
아닌 wrapper의 `runAsyncIfLeaderResult(slot, action).join()`을 호출한다.
테스트는 `LeaderRunResult.Elected`, 지정한 `leaderId`, 완료 후 동일
lockName 재획득을 확인한다.

```kotlin
val virtual = EtcdVirtualThreadLeaderElector(
    EtcdLeaderElector(client, EtcdLeaderElectionOptions(keyPrefix = prefix)),
)
val result = virtual.runAsyncIfLeaderResult(LeaderSlot(lockName, "etcd-vt")) { "ok" }.join()
(result as LeaderRunResult.Elected).leaderId shouldBeEqualTo "etcd-vt"
virtual.runAsyncIfLeader(lockName) { "reacquired" }.join() shouldBeEqualTo "reacquired"
```

`LeaderElectorBridgeLog`의 dropped count도 0인지 확인하고, group executor
overload가 source에서 지원되면 같은 slice의 direct test에 `VirtualThreadExecutor`
명시 호출을 추가한다.

- [ ] **Step 5: etcd slice를 모듈 테스트로 검증하고 matrix를 갱신한다.**

Run:

```bash
./gradlew :bluetape4k-leader-etcd:test --no-daemon --console=plain
python3 scripts/ci/validate_leader_contract_matrix.py --static
```

Expected: etcd module test 성공, matrix static 성공. 실패하면 contract
fixture가 사용하는 key prefix/lease timeout을 backend 규칙에 맞게 조정하고
production source는 변경하지 않는다.

- [ ] **Step 6: etcd slice를 커밋한다.**

```bash
git add leader-etcd/src/test scripts/ci/leader-contract-capabilities.json
git commit -m "test: etcd에 공통 leader contract fixture 적용"
```

## Task 3: Consul contract slice

**Files:**

- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulAsyncLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulAsyncLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulSuspendLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulSuspendLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulLockExtenderContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulGroupLockExtenderContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulSuspendLockExtenderContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulSuspendGroupLockExtenderContractTest.kt`
- Create: `leader-consul/src/test/kotlin/io/bluetape4k/leader/consul/contract/ConsulExecutorOverloadContractTest.kt`
- Modify: `scripts/ci/leader-contract-capabilities.json`

- [ ] **Step 1: Consul client/endpoint helper를 재사용하는 concrete subclasses를 추가한다.**

각 class는 `ConsulServer.Launcher.consul`을 companion에 두고
`ConsulEndpoint`를 `endpoint()` private helper로 만든다. 모든 option은
Consul의 최소 lease 10초를 지키도록 `LeaderElectionOptions(waitTime = 1.seconds,
leaseTime = 10.seconds)`를 사용한다. 생성자는 다음 public 형태만 사용한다.

```kotlin
ConsulLeaderElector(endpoint(), ConsulLeaderElectionOptions(leaderOptions = options, keyPrefix = prefix))
ConsulLeaderGroupElector(endpoint(), ConsulLeaderGroupElectionOptions(
    leaderGroupOptions = options, keyPrefix = prefix,
))
ConsulSuspendLeaderElector(endpoint(), ConsulLeaderElectionOptions(...))
ConsulSuspendLeaderGroupElector(endpoint(), ConsulLeaderGroupElectionOptions(...))
```

각 class의 return type은 해당 abstract fixture가 요구하는
`LeaderElector`, `LeaderGroupElector`, `AsyncLeaderElector`,
`AsyncLeaderGroupElector`, `SuspendLeaderElector`,
`SuspendLeaderGroupElector`로 명시한다.

- [ ] **Step 2: Consul 네 LockExtender fixture를 추가한다.**

`AbstractSyncLockExtenderContractTest`, `AbstractGroupLockExtenderContractTest`,
`AbstractSuspendLockExtenderContractTest`,
`AbstractSuspendGroupLockExtenderContractTest`를 각각 상속하고 group은
`LeaderGroupElectionOptions(maxLeaders = 2, leaseTime = 10.seconds)`를
사용한다. `KLoggingChannel`은 suspend classes에서만 사용한다.

- [ ] **Step 3: Consul executor overload를 직접 검증한다.**

`ConsulLeaderElector.runAsyncIfLeaderResult(slot, VirtualThreadExecutor)`와
`ConsulLeaderGroupElector.runAsyncIfLeaderResult(slot, VirtualThreadExecutor)`를
직접 호출해 `Elected.leaderId`를 확인한다. action 완료 후 같은 lockName을
동일 endpoint의 새 elector가 재획득하는 lease release assertion을 둔다.

- [ ] **Step 4: Consul slice를 검증하고 커밋한다.**

```bash
./gradlew :bluetape4k-leader-consul:test --no-daemon --console=plain
python3 scripts/ci/validate_leader_contract_matrix.py --static
git add leader-consul/src/test scripts/ci/leader-contract-capabilities.json
git commit -m "test: Consul에 공통 leader contract fixture 적용"
```

Expected: module test와 matrix static 모두 성공.

## Task 4: DynamoDB contract slice

**Files:**

- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbAsyncLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbAsyncLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbSuspendLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbSuspendLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbLockExtenderContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbGroupLockExtenderContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbSuspendLockExtenderContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbSuspendGroupLockExtenderContractTest.kt`
- Create: `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/contract/DynamoDbVirtualThreadContractTest.kt`
- Modify: `scripts/ci/leader-contract-capabilities.json`

- [ ] **Step 1: DynamoDB sync/async subclasses를 shared table 기반으로 추가한다.**

`AbstractDynamoDbLeaderTest`의 `dynamoDb`, `tableName`을 사용하고 모든
elector options에 unique `keyPrefix = keyPrefix()`를 지정한다. single
options는 다음 모양이다.

```kotlin
private fun options(leaderOptions: LeaderElectionOptions) =
    DynamoDbLeaderElectionOptions(
        leaderOptions = leaderOptions,
        tableName = tableName,
        keyPrefix = keyPrefix(),
    )

override fun createElector(options: LeaderElectionOptions): LeaderElector =
    DynamoDbLeaderElector(dynamoDb, options(options))
```

group은 `DynamoDbLeaderGroupElectionOptions(leaderGroupOptions = options,
tableName = tableName, keyPrefix = keyPrefix())`를 사용한다. async fixture는
blocking class의 `LeaderElector`/`LeaderGroupElector` 상속 API를 검증한다.

- [ ] **Step 2: DynamoDB suspend/LockExtender subclasses를 추가한다.**

suspend classes는 `dynamoDbAsync`를 사용하고 `KLoggingChannel`을 둔다.
LockExtender classes는 `DynamoDbLeaderElector`/`DynamoDbLeaderGroupElector`
및 async 대응 suspend classes를 각각 property로 제공한다. group은
`maxLeaders = 2`를 고정한다.

- [ ] **Step 3: 두 DynamoDB virtual wrapper를 직접 검증한다.**

`DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(...))`와
`DynamoDbVirtualThreadLeaderGroupElector(DynamoDbLeaderGroupElector(...))`
를 생성해 slot `runAsyncIfLeaderResult`를 각각 호출한다. 결과의
`leaderId`, action 반환값, wrapper 완료 뒤 새 elector의 재획득을 확인한다.
`VirtualFuture.toCompletableFuture().join()`을 사용해 JUnit thread에서
결과를 결정적으로 기다린다.

- [ ] **Step 4: DynamoDB slice를 검증하고 커밋한다.**

```bash
./gradlew :bluetape4k-leader-dynamodb:test --no-daemon --console=plain
python3 scripts/ci/validate_leader_contract_matrix.py --static
git add leader-dynamodb/src/test scripts/ci/leader-contract-capabilities.json
git commit -m "test: DynamoDB에 공통 leader contract fixture 적용"
```

Expected: DynamoDB Local 기반 module test와 matrix static 모두 성공.

## Task 5: Kubernetes Lease contract slice

**Files:**

- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseAsyncLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseAsyncLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseSuspendLeaderElectorLeaderIdContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseSuspendLeaderGroupElectorLeaderIdContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseLockExtenderContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseGroupLockExtenderContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseSuspendLockExtenderContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseSuspendGroupLockExtenderContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseExecutorOverloadContractTest.kt`
- Create: `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/contract/KubernetesLeaseContractSupport.kt`
- Modify: `scripts/ci/leader-contract-capabilities.json`

- [ ] **Step 1: K3s-tagged concrete subclasses를 추가한다.**

각 class에 `@Tag("k8s")`와 `@TestInstance(PER_CLASS)`를 붙인다.
`contract/KubernetesLeaseContractSupport.kt`를 하나 추가해
`K3sServer.Launcher.k3s.kubernetesClient()`로 client를 만들고 `close()`로
닫는 수명주기만 소유하게 한다. 각 concrete class는
`private val support = KubernetesLeaseContractSupport()`를 만들고
`@AfterAll fun closeClient() = support.close()`를 선언한다. 이 helper는
fixture logic을 소유하지 않는다.

single constructor는 다음 exact API를 사용한다.

```kotlin
KubernetesLeaseLeaderElector(
    client,
    KubernetesLeaseOptions(
        leaderOptions = options,
        namespace = "default",
        retryDelay = 50.milliseconds,
    ),
)
```

group/suspend는 각각 `KubernetesLeaseGroupOptions`와 public
`KubernetesLeaseSuspend*` constructor를 사용한다. group fixture의
`maxLeaders = 2`를 유지한다.

- [ ] **Step 2: K8s LockExtender 및 async subclasses를 추가한다.**

sync/suspend LockExtender 네 classes는 K3s tag를 유지하고 fresh random
lock name을 사용한다. async leader-id classes는 blocking elector의
`LeaderElector : AsyncLeaderElector` 상속을 확인한다. leaseTime은 K3s
test가 사용하는 1초보다 여유 있게 5초 이상으로 설정해 fixture의
60초 extension 호출이 backend minimum lease validation을 건드리지 않게
한다.

- [ ] **Step 3: K8s executor/slot overload를 직접 검증한다.**

`KubernetesLeaseLeaderElector.runAsyncIfLeaderResult(slot,
VirtualThreadExecutor)`와 `KubernetesLeaseLeaderGroupElector`의 동일
overload를 직접 호출한다. `Elected.leaderId`와 action return value를
확인하고, `k8sTest`에서만 실행되도록 class에 `@Tag("k8s")`를 둔다.

- [ ] **Step 4: K8s test와 matrix를 검증하고 커밋한다.**

```bash
./gradlew :bluetape4k-leader-k8s:test :bluetape4k-leader-k8s:k8sTest \
  --no-daemon --console=plain
python3 scripts/ci/validate_leader_contract_matrix.py --static
git add leader-k8s/src/test scripts/ci/leader-contract-capabilities.json
git commit -m "test: Kubernetes Lease에 공통 leader contract fixture 적용"
```

Expected: 일반 test와 `k8sTest` 모두 성공. K3s unavailable이면 compile 및
matrix 검증을 유지하고 runtime을 `PENDING`으로 기록한다.

## Task 6: 통합 검증과 7-tier review 준비

**Files:**

- Modify: `docs/superpowers/plans/2026-08-12-issue-671-contract-fixtures.md`
- Modify: `docs/superpowers/specs/2026-08-12-issue-671-contract-fixtures-design.md`
- No production source changes expected

- [ ] **Step 1: 전체 test-only 변경과 CI 연결을 점검한다.**

```bash
git diff --check
python3 scripts/ci/validate_leader_contract_matrix.py --static
python3 scripts/ci/validate_leader_contract_matrix.py --self-test
python3 scripts/ci/validate_ci_fanout.py --static
python3 scripts/ci/validate_ci_fanout.py --self-test
```

Expected: 모두 exit code 0; `git diff --check` 출력 없음.

- [ ] **Step 2: 네 모듈 compile/test를 순차 실행한다.**

```bash
./gradlew :bluetape4k-leader-etcd:test \
  :bluetape4k-leader-consul:test \
  :bluetape4k-leader-dynamodb:test \
  --no-daemon --console=plain
./gradlew :bluetape4k-leader-k8s:test :bluetape4k-leader-k8s:k8sTest \
  --no-daemon --console=plain
```

Expected: 실패 0. 컨테이너 환경 제약이 있으면 각 모듈별 compile과 실패
로그를 남기고 DoD를 `PENDING`으로 유지한다.

- [ ] **Step 3: 7-tier review 입력을 만들고 독립 lane을 dispatch한다.**

review 대상은 production source가 아닌 새 contract tests, matrix,
validator, CI 호출이다. 다음 관점으로 독립 검토한다.

1. requirements/issue acceptance
2. API/contract correctness
3. Kotlin/test idioms
4. backend integration/lifecycle
5. CI/fan-out and matrix drift
6. failure/concurrency/cancellation behavior
7. maintainability/docs boundary

각 lane은 fresh diff와 명령 출력이 있어야 하며, lane이 만료되면 3회까지
재시도하고 같은 실패가 반복되면 inline review로 대체하지 않고 `PENDING`을
기록한다. 사용자가 요청한 독립 review 재시도 규칙을 그대로 적용한다.

- [ ] **Step 4: issue/PR delivery 전 DoD를 기록한다.**

DoD에는 plan item별 상태, 변경 파일, 각 모듈 test 결과, matrix/CI
validator 결과, review finding 및 남은 위험을 한국어로 기록한다. 모든
fresh review가 완료되고 HIGH/CRITICAL finding이 0일 때만 PR 생성 gate로
넘어간다.

## 커밋 규칙

각 task 커밋은 다음 Lore 형식을 따른다.

```text
<왜 이 계약을 고정하는지 한 줄>

Constraint: issue #671은 test-only contract adoption과 명시적 N/A를 요구한다.
Rejected: production API 변경 | 이번 이슈 범위를 벗어나고 #672와 충돌한다.
Confidence: high
Scope-risk: moderate
Directive: 새 backend 추가 시 matrix와 concrete fixture를 같은 커밋에 갱신한다.
Tested: <실행한 module/validator 명령>
Not-tested: <환경 제약으로 실행하지 못한 항목 또는 none>
```

## 계획 자체 검토

- **Spec coverage:** concrete leader-id/LockExtender subclass는 Task 2–5,
  virtual/slot/lease direct test는 각 slice의 direct step, 명시적 N/A와
  matrix drift 방지는 Task 1, CI fan-out은 Task 1 Step 3와 Task 6에서
  다룬다. README/#672와 production 변경 제외는 모든 task의 Files 경계로
  유지한다.
- **Placeholder scan:** `TBD`, `TODO`, "similar to", "fill in"과
  미정 helper 이름을 제거했다. 모든 실행 명령은 대상 경로와 기대 결과를
  포함한다.
- **Type consistency:** leader-id fixture의 `createElector`는 sync/async에서
  `LeaderElector` 또는 `LeaderGroupElector`, suspend에서
  `SuspendLeaderElector` 또는 `SuspendLeaderGroupElector`를 반환한다.
  LockExtender fixture는 동일한 `elector` property 타입을 사용하고,
  virtual direct test만 `VirtualFuture`를 기다린다.
- **Environment boundary:** etcd/Consul/DynamoDB는 module `test`, K8s는
  `test`와 `k8sTest`를 모두 실행한다. K3s client는
  `KubernetesLeaseContractSupport`가 생성/종료를 책임진다.
